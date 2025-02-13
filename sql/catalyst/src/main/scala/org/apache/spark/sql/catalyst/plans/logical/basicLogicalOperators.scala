/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.plans.logical

import org.apache.spark.sql.catalyst.AliasIdentifier
import org.apache.spark.sql.catalyst.analysis.{EliminateView, MultiInstanceRelation}
import org.apache.spark.sql.catalyst.catalog.{CatalogStorageFormat, CatalogTable}
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.aggregate.AggregateExpression
import org.apache.spark.sql.catalyst.plans._
import org.apache.spark.sql.catalyst.plans.physical.{HashPartitioning, Partitioning, RangePartitioning, RoundRobinPartitioning}
import org.apache.spark.sql.catalyst.util.truncatedString
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types._
import org.apache.spark.util.random.RandomSampler

/**
 * When planning take() or collect() operations, this special node that is inserted at the top of
 * the logical plan before invoking the query planner.
 *
 * Rules can pattern-match on this node in order to apply transformations that only take effect
 * at the top of the logical query plan.
 */
case class ReturnAnswer(child: LogicalPlan) extends UnaryNode {
  override def output: Seq[Attribute] = child.output
}

/**
 * This node is inserted at the top of a subquery when it is optimized. This makes sure we can
 * recognize a subquery as such, and it allows us to write subquery aware transformations.
 *
 * @param correlated flag that indicates the subquery is correlated, and will be rewritten into a
 *                   join during analysis.
 */
case class Subquery(child: LogicalPlan, correlated: Boolean) extends OrderPreservingUnaryNode {
  override def output: Seq[Attribute] = child.output
}

object Subquery {
  def fromExpression(s: SubqueryExpression): Subquery =
    Subquery(s.plan, SubqueryExpression.hasCorrelatedSubquery(s))
}

case class Project(projectList: Seq[NamedExpression], child: LogicalPlan)
    extends OrderPreservingUnaryNode {
  override def output: Seq[Attribute] = projectList.map(_.toAttribute)
  override def metadataOutput: Seq[Attribute] = Nil
  override def maxRows: Option[Long] = child.maxRows

  override lazy val resolved: Boolean = {
    val hasSpecialExpressions = projectList.exists ( _.collect {
        case agg: AggregateExpression => agg
        case generator: Generator => generator
        case window: WindowExpression => window
      }.nonEmpty
    )

    !expressions.exists(!_.resolved) && childrenResolved && !hasSpecialExpressions
  }

  override lazy val validConstraints: ExpressionSet =
    getAllValidConstraints(projectList)
}

/**
 * Applies a [[Generator]] to a stream of input rows, combining the
 * output of each into a new stream of rows.  This operation is similar to a `flatMap` in functional
 * programming with one important additional feature, which allows the input rows to be joined with
 * their output.
 *
 * @param generator the generator expression
 * @param unrequiredChildIndex this parameter starts as Nil and gets filled by the Optimizer.
 *                             It's used as an optimization for omitting data generation that will
 *                             be discarded next by a projection.
 *                             A common use case is when we explode(array(..)) and are interested
 *                             only in the exploded data and not in the original array. before this
 *                             optimization the array got duplicated for each of its elements,
 *                             causing O(n^^2) memory consumption. (see [SPARK-21657])
 * @param outer when true, each input row will be output at least once, even if the output of the
 *              given `generator` is empty.
 * @param qualifier Qualifier for the attributes of generator(UDTF)
 * @param generatorOutput The output schema of the Generator.
 * @param child Children logical plan node
 */
case class Generate(
    generator: Generator,
    unrequiredChildIndex: Seq[Int],
    outer: Boolean,
    qualifier: Option[String],
    generatorOutput: Seq[Attribute],
    child: LogicalPlan)
  extends UnaryNode {

  lazy val requiredChildOutput: Seq[Attribute] = {
    val unrequiredSet = unrequiredChildIndex.toSet
    child.output.zipWithIndex.filterNot(t => unrequiredSet.contains(t._2)).map(_._1)
  }

  override lazy val resolved: Boolean = {
    generator.resolved &&
      childrenResolved &&
      generator.elementSchema.length == generatorOutput.length &&
      generatorOutput.forall(_.resolved)
  }

  override def producedAttributes: AttributeSet = AttributeSet(generatorOutput)

  def qualifiedGeneratorOutput: Seq[Attribute] = {
    val qualifiedOutput = qualifier.map { q =>
      // prepend the new qualifier to the existed one
      generatorOutput.map(a => a.withQualifier(Seq(q)))
    }.getOrElse(generatorOutput)
    val nullableOutput = qualifiedOutput.map {
      // if outer, make all attributes nullable, otherwise keep existing nullability
      a => a.withNullability(outer || a.nullable)
    }
    nullableOutput
  }

  def output: Seq[Attribute] = requiredChildOutput ++ qualifiedGeneratorOutput
}

case class Filter(condition: Expression, child: LogicalPlan)
  extends OrderPreservingUnaryNode with PredicateHelper {
  override def output: Seq[Attribute] = child.output

  override def maxRows: Option[Long] = child.maxRows

  override protected lazy val validConstraints: ExpressionSet = {
    val predicates = splitConjunctivePredicates(condition)
      .filterNot(SubqueryExpression.hasCorrelatedSubquery)
    child.constraints.union(ExpressionSet(predicates))
  }
}

abstract class SetOperation(left: LogicalPlan, right: LogicalPlan) extends BinaryNode {

  def duplicateResolved: Boolean = left.outputSet.intersect(right.outputSet).isEmpty

  protected def leftConstraints: ExpressionSet = left.constraints

  protected def rightConstraints: ExpressionSet = {
    require(left.output.size == right.output.size)
    val attributeRewrites = AttributeMap(right.output.zip(left.output))
    right.constraints.map(_ transform {
      case a: Attribute => attributeRewrites(a)
    })
  }

  override lazy val resolved: Boolean =
    childrenResolved &&
      left.output.length == right.output.length &&
      left.output.zip(right.output).forall { case (l, r) =>
        l.dataType.sameType(r.dataType)
      } && duplicateResolved
}

object SetOperation {
  def unapply(p: SetOperation): Option[(LogicalPlan, LogicalPlan)] = Some((p.left, p.right))
}

case class Intersect(
    left: LogicalPlan,
    right: LogicalPlan,
    isAll: Boolean) extends SetOperation(left, right) {

  override def nodeName: String = getClass.getSimpleName + ( if ( isAll ) "All" else "" )

  override def output: Seq[Attribute] =
    left.output.zip(right.output).map { case (leftAttr, rightAttr) =>
      leftAttr.withNullability(leftAttr.nullable && rightAttr.nullable)
    }

  override def metadataOutput: Seq[Attribute] = Nil

  override protected lazy val validConstraints: ExpressionSet =
    leftConstraints.union(rightConstraints)

  override def maxRows: Option[Long] = {
    if (children.exists(_.maxRows.isEmpty)) {
      None
    } else {
      Some(children.flatMap(_.maxRows).min)
    }
  }
}

case class Except(
    left: LogicalPlan,
    right: LogicalPlan,
    isAll: Boolean) extends SetOperation(left, right) {
  override def nodeName: String = getClass.getSimpleName + ( if ( isAll ) "All" else "" )
  /** We don't use right.output because those rows get excluded from the set. */
  override def output: Seq[Attribute] = left.output

  override def metadataOutput: Seq[Attribute] = Nil

  override protected lazy val validConstraints: ExpressionSet = leftConstraints
}

/** Factory for constructing new `Union` nodes. */
object Union {
  def apply(left: LogicalPlan, right: LogicalPlan): Union = {
    Union (left :: right :: Nil)
  }
}

/**
 * Logical plan for unioning two plans, without a distinct. This is UNION ALL in SQL.
 *
 * @param byName          Whether resolves columns in the children by column names.
 * @param allowMissingCol Allows missing columns in children query plans. If it is true,
 *                        this function allows different set of column names between two Datasets.
 *                        This can be set to true only if `byName` is true.
 */
case class Union(
    children: Seq[LogicalPlan],
    byName: Boolean = false,
    allowMissingCol: Boolean = false) extends LogicalPlan {
  assert(!allowMissingCol || byName, "`allowMissingCol` can be true only if `byName` is true.")

  override def maxRows: Option[Long] = {
    var sum = BigInt(0)
    children.foreach { child =>
      if (child.maxRows.isDefined) {
        sum += child.maxRows.get
        if (!sum.isValidLong) {
          return None
        }
      } else {
        return None
      }
    }
    Some(sum.toLong)
  }

  /**
   * Note the definition has assumption about how union is implemented physically.
   */
  override def maxRowsPerPartition: Option[Long] = {
    var sum = BigInt(0)
    children.foreach { child =>
      if (child.maxRowsPerPartition.isDefined) {
        sum += child.maxRowsPerPartition.get
        if (!sum.isValidLong) {
          return None
        }
      } else {
        return None
      }
    }
    Some(sum.toLong)
  }

  def duplicateResolved: Boolean = {
    children.map(_.outputSet.size).sum ==
      AttributeSet.fromAttributeSets(children.map(_.outputSet)).size
  }

  // updating nullability to make all the children consistent
  override def output: Seq[Attribute] = {
    children.map(_.output).transpose.map { attrs =>
      val firstAttr = attrs.head
      val nullable = attrs.exists(_.nullable)
      val newDt = attrs.map(_.dataType).reduce(StructType.merge)
      if (firstAttr.dataType == newDt) {
        firstAttr.withNullability(nullable)
      } else {
        AttributeReference(firstAttr.name, newDt, nullable, firstAttr.metadata)(
          firstAttr.exprId, firstAttr.qualifier)
      }
    }
  }

  override def metadataOutput: Seq[Attribute] = Nil

  override lazy val resolved: Boolean = {
    // allChildrenCompatible needs to be evaluated after childrenResolved
    def allChildrenCompatible: Boolean =
      children.tail.forall( child =>
        // compare the attribute number with the first child
        child.output.length == children.head.output.length &&
        // compare the data types with the first child
        child.output.zip(children.head.output).forall {
          case (l, r) => l.dataType.sameType(r.dataType)
        })
    children.length > 1 && !(byName || allowMissingCol) && childrenResolved && allChildrenCompatible
  }

  /**
   * Maps the constraints containing a given (original) sequence of attributes to those with a
   * given (reference) sequence of attributes. Given the nature of union, we expect that the
   * mapping between the original and reference sequences are symmetric.
   */
  private def rewriteConstraints(
      reference: Seq[Attribute],
      original: Seq[Attribute],
      constraints: ExpressionSet): ExpressionSet = {
    require(reference.size == original.size)
    val attributeRewrites = AttributeMap(original.zip(reference))
    constraints.map(_ transform {
      case a: Attribute => attributeRewrites(a)
    })
  }

  private def merge(a: ExpressionSet, b: ExpressionSet): ExpressionSet = {
    val common = a.intersect(b)
    // The constraint with only one reference could be easily inferred as predicate
    // Grouping the constraints by it's references so we can combine the constraints with same
    // reference together
    val othera = a.diff(common).filter(_.references.size == 1).groupBy(_.references.head)
    val otherb = b.diff(common).filter(_.references.size == 1).groupBy(_.references.head)
    // loose the constraints by: A1 && B1 || A2 && B2  ->  (A1 || A2) && (B1 || B2)
    val others = (othera.keySet intersect otherb.keySet).map { attr =>
      Or(othera(attr).reduceLeft(And), otherb(attr).reduceLeft(And))
    }
    common ++ others
  }

  override protected lazy val validConstraints: ExpressionSet = {
    children
      .map(child => rewriteConstraints(children.head.output, child.output, child.constraints))
      .reduce(merge(_, _))
  }
}

case class Join(
    left: LogicalPlan,
    right: LogicalPlan,
    joinType: JoinType,
    condition: Option[Expression],
    hint: JoinHint)
  extends BinaryNode with PredicateHelper {

  override def output: Seq[Attribute] = {
    joinType match {
      case j: ExistenceJoin =>
        left.output :+ j.exists
      case LeftExistence(_) =>
        left.output
      case LeftOuter =>
        left.output ++ right.output.map(_.withNullability(true))
      case RightOuter =>
        left.output.map(_.withNullability(true)) ++ right.output
      case FullOuter =>
        left.output.map(_.withNullability(true)) ++ right.output.map(_.withNullability(true))
      case _ =>
        left.output ++ right.output
    }
  }

  override def metadataOutput: Seq[Attribute] = {
    joinType match {
      case ExistenceJoin(_) =>
        left.metadataOutput
      case LeftExistence(_) =>
        left.metadataOutput
      case _ =>
        children.flatMap(_.metadataOutput)
    }
  }

  override protected lazy val validConstraints: ExpressionSet = {
    joinType match {
      case _: InnerLike if condition.isDefined =>
        left.constraints
          .union(right.constraints)
          .union(ExpressionSet(splitConjunctivePredicates(condition.get)))
      case LeftSemi if condition.isDefined =>
        left.constraints
          .union(ExpressionSet(splitConjunctivePredicates(condition.get)))
      case j: ExistenceJoin =>
        left.constraints
      case _: InnerLike =>
        left.constraints.union(right.constraints)
      case LeftExistence(_) =>
        left.constraints
      case LeftOuter =>
        left.constraints
      case RightOuter =>
        right.constraints
      case _ =>
        ExpressionSet()
    }
  }

  def duplicateResolved: Boolean = left.outputSet.intersect(right.outputSet).isEmpty

  // Joins are only resolved if they don't introduce ambiguous expression ids.
  // NaturalJoin should be ready for resolution only if everything else is resolved here
  lazy val resolvedExceptNatural: Boolean = {
    childrenResolved &&
      expressions.forall(_.resolved) &&
      duplicateResolved &&
      condition.forall(_.dataType == BooleanType)
  }

  // if not a natural join, use `resolvedExceptNatural`. if it is a natural join or
  // using join, we still need to eliminate natural or using before we mark it resolved.
  override lazy val resolved: Boolean = joinType match {
    case NaturalJoin(_) => false
    case UsingJoin(_, _) => false
    case _ => resolvedExceptNatural
  }

  // Ignore hint for canonicalization
  protected override def doCanonicalize(): LogicalPlan =
    super.doCanonicalize().asInstanceOf[Join].copy(hint = JoinHint.NONE)

  // Do not include an empty join hint in string description
  protected override def stringArgs: Iterator[Any] = super.stringArgs.filter { e =>
    (!e.isInstanceOf[JoinHint]
      || e.asInstanceOf[JoinHint].leftHint.isDefined
      || e.asInstanceOf[JoinHint].rightHint.isDefined)
  }
}

/**
 * Insert query result into a directory.
 *
 * @param isLocal Indicates whether the specified directory is local directory
 * @param storage Info about output file, row and what serialization format
 * @param provider Specifies what data source to use; only used for data source file.
 * @param child The query to be executed
 * @param overwrite If true, the existing directory will be overwritten
 *
 * Note that this plan is unresolved and has to be replaced by the concrete implementations
 * during analysis.
 */
case class InsertIntoDir(
    isLocal: Boolean,
    storage: CatalogStorageFormat,
    provider: Option[String],
    child: LogicalPlan,
    overwrite: Boolean = true)
  extends UnaryNode {

  override def output: Seq[Attribute] = Seq.empty
  override def metadataOutput: Seq[Attribute] = Nil
  override lazy val resolved: Boolean = false
}

/**
 * A container for holding the view description(CatalogTable), and the output of the view. The
 * child should be a logical plan parsed from the `CatalogTable.viewText`, should throw an error
 * if the `viewText` is not defined.
 * This operator will be removed at the end of analysis stage.
 *
 * @param desc A view description(CatalogTable) that provides necessary information to resolve the
 *             view.
 * @param output The output of a view operator, this is generated during planning the view, so that
 *               we are able to decouple the output from the underlying structure.
 * @param child The logical plan of a view operator, it should be a logical plan parsed from the
 *              `CatalogTable.viewText`, should throw an error if the `viewText` is not defined.
 */
case class View(
    desc: CatalogTable,
    isTempView: Boolean,
    output: Seq[Attribute],
    child: LogicalPlan) extends LogicalPlan with MultiInstanceRelation {

  override def producedAttributes: AttributeSet = outputSet

  override lazy val resolved: Boolean = child.resolved

  override def children: Seq[LogicalPlan] = child :: Nil

  override def newInstance(): LogicalPlan = copy(output = output.map(_.newInstance()))

  override def metadataOutput: Seq[Attribute] = Nil

  override def simpleString(maxFields: Int): String = {
    s"View (${desc.identifier}, ${output.mkString("[", ",", "]")})"
  }

  override def doCanonicalize(): LogicalPlan = {
    def sameOutput(
      outerProject: Seq[NamedExpression], innerProject: Seq[NamedExpression]): Boolean = {
      outerProject.length == innerProject.length &&
        outerProject.zip(innerProject).forall {
          case(outer, inner) => outer.name == inner.name && outer.dataType == inner.dataType
        }
    }

    val eliminated = EliminateView(this) match {
      case Project(viewProjectList, child @ Project(queryProjectList, _))
        if sameOutput(viewProjectList, queryProjectList) =>
        child
      case other => other
    }
    eliminated.canonicalized
  }
}

object View {
  def effectiveSQLConf(configs: Map[String, String], isTempView: Boolean): SQLConf = {
    val activeConf = SQLConf.get
    // For temporary view, we always use captured sql configs
    if (activeConf.useCurrentSQLConfigsForView && !isTempView) return activeConf

    val sqlConf = new SQLConf()
    // We retain below configs from current session because they are not captured by view
    // as optimization configs but they are still needed during the view resolution.
    // TODO: remove this `retainedConfigs` after the `RelationConversions` is moved to
    // optimization phase.
    val retainedConfigs = activeConf.getAllConfs.filterKeys(key =>
      Seq(
        "spark.sql.hive.convertMetastoreParquet",
        "spark.sql.hive.convertMetastoreOrc",
        "spark.sql.hive.convertInsertingPartitionedTable",
        "spark.sql.hive.convertMetastoreCtas"
      ).contains(key))
    for ((k, v) <- configs ++ retainedConfigs) {
      sqlConf.settings.put(k, v)
    }
    sqlConf
  }
}

/**
 * A container for holding named common table expressions (CTEs) and a query plan.
 * This operator will be removed during analysis and the relations will be substituted into child.
 *
 * @param child The final query of this CTE.
 * @param cteRelations A sequence of pair (alias, the CTE definition) that this CTE defined
 *                     Each CTE can see the base tables and the previously defined CTEs only.
 */
case class With(child: LogicalPlan, cteRelations: Seq[(String, SubqueryAlias)]) extends UnaryNode {
  override def output: Seq[Attribute] = child.output

  override def simpleString(maxFields: Int): String = {
    val cteAliases = truncatedString(cteRelations.map(_._1), "[", ", ", "]", maxFields)
    s"CTE $cteAliases"
  }

  override def innerChildren: Seq[LogicalPlan] = cteRelations.map(_._2)
}

case class WithWindowDefinition(
    windowDefinitions: Map[String, WindowSpecDefinition],
    child: LogicalPlan) extends UnaryNode {
  override def output: Seq[Attribute] = child.output
}

/**
 * @param order  The ordering expressions
 * @param global True means global sorting apply for entire data set,
 *               False means sorting only apply within the partition.
 * @param child  Child logical plan
 */
case class Sort(
    order: Seq[SortOrder],
    global: Boolean,
    child: LogicalPlan) extends UnaryNode {
  override def output: Seq[Attribute] = child.output
  override def maxRows: Option[Long] = child.maxRows
  override def outputOrdering: Seq[SortOrder] = order
}

/** Factory for constructing new `Range` nodes. */
object Range {
  def apply(start: Long, end: Long, step: Long,
            numSlices: Option[Int], isStreaming: Boolean = false): Range = {
    val output = StructType(StructField("id", LongType, nullable = false) :: Nil).toAttributes
    new Range(start, end, step, numSlices, output, isStreaming)
  }
  def apply(start: Long, end: Long, step: Long, numSlices: Int): Range = {
    Range(start, end, step, Some(numSlices))
  }
}

case class Range(
    start: Long,
    end: Long,
    step: Long,
    numSlices: Option[Int],
    output: Seq[Attribute],
    override val isStreaming: Boolean)
  extends LeafNode with MultiInstanceRelation {

  require(step != 0, s"step ($step) cannot be 0")

  val numElements: BigInt = {
    val safeStart = BigInt(start)
    val safeEnd = BigInt(end)
    if ((safeEnd - safeStart) % step == 0 || (safeEnd > safeStart) != (step > 0)) {
      (safeEnd - safeStart) / step
    } else {
      // the remainder has the same sign with range, could add 1 more
      (safeEnd - safeStart) / step + 1
    }
  }

  def toSQL(): String = {
    if (numSlices.isDefined) {
      s"SELECT id AS `${output.head.name}` FROM range($start, $end, $step, ${numSlices.get})"
    } else {
      s"SELECT id AS `${output.head.name}` FROM range($start, $end, $step)"
    }
  }

  override def newInstance(): Range = copy(output = output.map(_.newInstance()))

  override def simpleString(maxFields: Int): String = {
    s"Range ($start, $end, step=$step, splits=$numSlices)"
  }

  override def computeStats(): Statistics = {
    Statistics(sizeInBytes = LongType.defaultSize * numElements)
  }

  override def outputOrdering: Seq[SortOrder] = {
    val order = if (step > 0) {
      Ascending
    } else {
      Descending
    }
    output.map(a => SortOrder(a, order))
  }
}

/**
 * This is a Group by operator with the aggregate functions and projections.
 *
 * @param groupingExpressions expressions for grouping keys
 * @param aggregateExpressions expressions for a project list, which could contain
 *                             [[AggregateFunction]]s.
 *
 * Note: Currently, aggregateExpressions is the project list of this Group by operator. Before
 * separating projection from grouping and aggregate, we should avoid expression-level optimization
 * on aggregateExpressions, which could reference an expression in groupingExpressions.
 * For example, see the rule [[org.apache.spark.sql.catalyst.optimizer.SimplifyExtractValueOps]]
 */
case class Aggregate(
    groupingExpressions: Seq[Expression],
    aggregateExpressions: Seq[NamedExpression],
    child: LogicalPlan)
  extends UnaryNode {

  override lazy val resolved: Boolean = {
    val hasWindowExpressions = aggregateExpressions.exists ( _.collect {
        case window: WindowExpression => window
      }.nonEmpty
    )

    !expressions.exists(!_.resolved) && childrenResolved && !hasWindowExpressions
  }

  override def output: Seq[Attribute] = aggregateExpressions.map(_.toAttribute)
  override def metadataOutput: Seq[Attribute] = Nil
  override def maxRows: Option[Long] = {
    if (groupingExpressions.isEmpty) {
      Some(1L)
    } else {
      child.maxRows
    }
  }

  override lazy val validConstraints: ExpressionSet = {
    val nonAgg = aggregateExpressions.filter(_.find(_.isInstanceOf[AggregateExpression]).isEmpty)
    getAllValidConstraints(nonAgg)
  }
}

case class Window(
    windowExpressions: Seq[NamedExpression],
    partitionSpec: Seq[Expression],
    orderSpec: Seq[SortOrder],
    child: LogicalPlan) extends UnaryNode {

  override def output: Seq[Attribute] =
    child.output ++ windowExpressions.map(_.toAttribute)

  override def producedAttributes: AttributeSet = windowOutputSet

  def windowOutputSet: AttributeSet = AttributeSet(windowExpressions.map(_.toAttribute))
}

object Expand {
  /**
   * Build bit mask from attributes of selected grouping set. A bit in the bitmask is corresponding
   * to an attribute in group by attributes sequence, the selected attribute has corresponding bit
   * set to 0 and otherwise set to 1. For example, if we have GroupBy attributes (a, b, c, d), the
   * bitmask 5(whose binary form is 0101) represents grouping set (a, c).
   *
   * @param groupingSetAttrs The attributes of selected grouping set
   * @param attrMap Mapping group by attributes to its index in attributes sequence
   * @return The bitmask which represents the selected attributes out of group by attributes.
   */
  private def buildBitmask(
    groupingSetAttrs: Seq[Attribute],
    attrMap: Map[Attribute, Int]): Long = {
    val numAttributes = attrMap.size
    assert(numAttributes <= GroupingID.dataType.defaultSize * 8)
    val mask = if (numAttributes != 64) (1L << numAttributes) - 1 else 0xFFFFFFFFFFFFFFFFL
    // Calculate the attrbute masks of selected grouping set. For example, if we have GroupBy
    // attributes (a, b, c, d), grouping set (a, c) will produce the following sequence:
    // (15, 7, 13), whose binary form is (1111, 0111, 1101)
    val masks = (mask +: groupingSetAttrs.map(attrMap).map(index =>
      // 0 means that the column at the given index is a grouping column, 1 means it is not,
      // so we unset the bit in bitmap.
      ~(1L << (numAttributes - 1 - index))
    ))
    // Reduce masks to generate an bitmask for the selected grouping set.
    masks.reduce(_ & _)
  }

  /**
   * Apply the all of the GroupExpressions to every input row, hence we will get
   * multiple output rows for an input row.
   *
   * @param groupingSetsAttrs The attributes of grouping sets
   * @param groupByAliases The aliased original group by expressions
   * @param groupByAttrs The attributes of aliased group by expressions
   * @param gid Attribute of the grouping id
   * @param child Child operator
   */
  def apply(
    groupingSetsAttrs: Seq[Seq[Attribute]],
    groupByAliases: Seq[Alias],
    groupByAttrs: Seq[Attribute],
    gid: Attribute,
    child: LogicalPlan): Expand = {
    val attrMap = groupByAttrs.zipWithIndex.toMap

    val hasDuplicateGroupingSets = groupingSetsAttrs.size !=
      groupingSetsAttrs.map(_.map(_.exprId).toSet).distinct.size

    // Create an array of Projections for the child projection, and replace the projections'
    // expressions which equal GroupBy expressions with Literal(null), if those expressions
    // are not set for this grouping set.
    val projections = groupingSetsAttrs.zipWithIndex.map { case (groupingSetAttrs, i) =>
      val projAttrs = child.output ++ groupByAttrs.map { attr =>
        if (!groupingSetAttrs.contains(attr)) {
          // if the input attribute in the Invalid Grouping Expression set of for this group
          // replace it with constant null
          Literal.create(null, attr.dataType)
        } else {
          attr
        }
      // groupingId is the last output, here we use the bit mask as the concrete value for it.
      } :+ {
        val bitMask = buildBitmask(groupingSetAttrs, attrMap)
        val dataType = GroupingID.dataType
        Literal.create(if (dataType.sameType(IntegerType)) bitMask.toInt else bitMask, dataType)
      }

      if (hasDuplicateGroupingSets) {
        // If `groupingSetsAttrs` has duplicate entries (e.g., GROUPING SETS ((key), (key))),
        // we add one more virtual grouping attribute (`_gen_grouping_pos`) to avoid
        // wrongly grouping rows with the same grouping ID.
        projAttrs :+ Literal.create(i, IntegerType)
      } else {
        projAttrs
      }
    }

    // the `groupByAttrs` has different meaning in `Expand.output`, it could be the original
    // grouping expression or null, so here we create new instance of it.
    val output = if (hasDuplicateGroupingSets) {
      val gpos = AttributeReference("_gen_grouping_pos", IntegerType, false)()
      child.output ++ groupByAttrs.map(_.newInstance) :+ gid :+ gpos
    } else {
      child.output ++ groupByAttrs.map(_.newInstance) :+ gid
    }
    Expand(projections, output, Project(child.output ++ groupByAliases, child))
  }
}

/**
 * Apply a number of projections to every input row, hence we will get multiple output rows for
 * an input row.
 *
 * @param projections to apply
 * @param output of all projections.
 * @param child operator.
 */
case class Expand(
    projections: Seq[Seq[Expression]],
    output: Seq[Attribute],
    child: LogicalPlan) extends UnaryNode {
  @transient
  override lazy val references: AttributeSet =
    AttributeSet(projections.flatten.flatMap(_.references))

  override def metadataOutput: Seq[Attribute] = Nil

  override def producedAttributes: AttributeSet = AttributeSet(output diff child.output)

  // This operator can reuse attributes (for example making them null when doing a roll up) so
  // the constraints of the child may no longer be valid.
  override protected lazy val validConstraints: ExpressionSet = ExpressionSet()
}

/**
 * A GROUP BY clause with GROUPING SETS can generate a result set equivalent
 * to generated by a UNION ALL of multiple simple GROUP BY clauses.
 *
 * We will transform GROUPING SETS into logical plan Aggregate(.., Expand) in Analyzer
 *
 * @param selectedGroupByExprs A sequence of selected GroupBy expressions, all exprs should
 *                     exist in groupByExprs.
 * @param groupByExprs The Group By expressions candidates.
 * @param child        Child operator
 * @param aggregations The Aggregation expressions, those non selected group by expressions
 *                     will be considered as constant null if it appears in the expressions
 */
case class GroupingSets(
    selectedGroupByExprs: Seq[Seq[Expression]],
    groupByExprs: Seq[Expression],
    child: LogicalPlan,
    aggregations: Seq[NamedExpression]) extends UnaryNode {

  override def output: Seq[Attribute] = aggregations.map(_.toAttribute)

  // Needs to be unresolved before its translated to Aggregate + Expand because output attributes
  // will change in analysis.
  override lazy val resolved: Boolean = false
}

/**
 * A constructor for creating a pivot, which will later be converted to a [[Project]]
 * or an [[Aggregate]] during the query analysis.
 *
 * @param groupByExprsOpt A sequence of group by expressions. This field should be None if coming
 *                        from SQL, in which group by expressions are not explicitly specified.
 * @param pivotColumn     The pivot column.
 * @param pivotValues     A sequence of values for the pivot column.
 * @param aggregates      The aggregation expressions, each with or without an alias.
 * @param child           Child operator
 */
case class Pivot(
    groupByExprsOpt: Option[Seq[NamedExpression]],
    pivotColumn: Expression,
    pivotValues: Seq[Expression],
    aggregates: Seq[Expression],
    child: LogicalPlan) extends UnaryNode {
  override lazy val resolved = false // Pivot will be replaced after being resolved.
  override def output: Seq[Attribute] = {
    val pivotAgg = aggregates match {
      case agg :: Nil =>
        pivotValues.map(value => AttributeReference(value.toString, agg.dataType)())
      case _ =>
        pivotValues.flatMap { value =>
          aggregates.map(agg => AttributeReference(value + "_" + agg.sql, agg.dataType)())
        }
    }
    groupByExprsOpt.getOrElse(Seq.empty).map(_.toAttribute) ++ pivotAgg
  }
  override def metadataOutput: Seq[Attribute] = Nil
}

/**
 * A constructor for creating a logical limit, which is split into two separate logical nodes:
 * a [[LocalLimit]], which is a partition local limit, followed by a [[GlobalLimit]].
 *
 * This muds the water for clean logical/physical separation, and is done for better limit pushdown.
 * In distributed query processing, a non-terminal global limit is actually an expensive operation
 * because it requires coordination (in Spark this is done using a shuffle).
 *
 * In most cases when we want to push down limit, it is often better to only push some partition
 * local limit. Consider the following:
 *
 *   GlobalLimit(Union(A, B))
 *
 * It is better to do
 *   GlobalLimit(Union(LocalLimit(A), LocalLimit(B)))
 *
 * than
 *   Union(GlobalLimit(A), GlobalLimit(B)).
 *
 * So we introduced LocalLimit and GlobalLimit in the logical plan node for limit pushdown.
 */
object Limit {
  def apply(limitExpr: Expression, child: LogicalPlan): UnaryNode = {
    GlobalLimit(limitExpr, LocalLimit(limitExpr, child))
  }

  def unapply(p: GlobalLimit): Option[(Expression, LogicalPlan)] = {
    p match {
      case GlobalLimit(le1, LocalLimit(le2, child)) if le1 == le2 => Some((le1, child))
      case _ => None
    }
  }
}

/**
 * A global (coordinated) limit. This operator can emit at most `limitExpr` number in total.
 *
 * See [[Limit]] for more information.
 *
 * Note that, we can not make it inherit [[OrderPreservingUnaryNode]] due to the different strategy
 * of physical plan. The output ordering of child will be broken if a shuffle exchange comes in
 * between the child and global limit, due to the fact that shuffle reader fetches blocks in random
 * order.
 */
case class GlobalLimit(limitExpr: Expression, child: LogicalPlan) extends UnaryNode {
  override def output: Seq[Attribute] = child.output
  override def maxRows: Option[Long] = {
    limitExpr match {
      case IntegerLiteral(limit) => Some(limit)
      case _ => None
    }
  }
}

/**
 * A partition-local (non-coordinated) limit. This operator can emit at most `limitExpr` number
 * of tuples on each physical partition.
 *
 * See [[Limit]] for more information.
 */
case class LocalLimit(limitExpr: Expression, child: LogicalPlan) extends OrderPreservingUnaryNode {
  override def output: Seq[Attribute] = child.output

  override def maxRowsPerPartition: Option[Long] = {
    limitExpr match {
      case IntegerLiteral(limit) => Some(limit)
      case _ => None
    }
  }
}

/**
 * This is similar with [[Limit]] except:
 *
 * - It does not have plans for global/local separately because currently there is only single
 *   implementation which initially mimics both global/local tails. See
 *   `org.apache.spark.sql.execution.CollectTailExec` and
 *   `org.apache.spark.sql.execution.CollectLimitExec`
 *
 * - Currently, this plan can only be a root node.
 */
case class Tail(limitExpr: Expression, child: LogicalPlan) extends OrderPreservingUnaryNode {
  override def output: Seq[Attribute] = child.output
  override def maxRows: Option[Long] = {
    limitExpr match {
      case IntegerLiteral(limit) => Some(limit)
      case _ => None
    }
  }
}

/**
 * Aliased subquery.
 *
 * @param identifier the alias identifier for this subquery.
 * @param child the logical plan of this subquery.
 */
case class SubqueryAlias(
    identifier: AliasIdentifier,
    child: LogicalPlan)
  extends OrderPreservingUnaryNode {

  def alias: String = identifier.name

  override def output: Seq[Attribute] = {
    val qualifierList = identifier.qualifier :+ alias
    child.output.map(_.withQualifier(qualifierList))
  }

  override def metadataOutput: Seq[Attribute] = {
    val qualifierList = identifier.qualifier :+ alias
    child.metadataOutput.map(_.withQualifier(qualifierList))
  }

  override def doCanonicalize(): LogicalPlan = child.canonicalized
}

object SubqueryAlias {
  def apply(
      identifier: String,
      child: LogicalPlan): SubqueryAlias = {
    SubqueryAlias(AliasIdentifier(identifier), child)
  }

  def apply(
      identifier: String,
      database: String,
      child: LogicalPlan): SubqueryAlias = {
    SubqueryAlias(AliasIdentifier(identifier, Seq(database)), child)
  }

  def apply(
      multipartIdentifier: Seq[String],
      child: LogicalPlan): SubqueryAlias = {
    SubqueryAlias(AliasIdentifier(multipartIdentifier.last, multipartIdentifier.init), child)
  }
}
/**
 * Sample the dataset.
 *
 * @param lowerBound Lower-bound of the sampling probability (usually 0.0)
 * @param upperBound Upper-bound of the sampling probability. The expected fraction sampled
 *                   will be ub - lb.
 * @param withReplacement Whether to sample with replacement.
 * @param seed the random seed
 * @param child the LogicalPlan
 */
case class Sample(
    lowerBound: Double,
    upperBound: Double,
    withReplacement: Boolean,
    seed: Long,
    child: LogicalPlan) extends UnaryNode {

  val eps = RandomSampler.roundingEpsilon
  val fraction = upperBound - lowerBound
  if (withReplacement) {
    require(
      fraction >= 0.0 - eps,
      s"Sampling fraction ($fraction) must be nonnegative with replacement")
  } else {
    require(
      fraction >= 0.0 - eps && fraction <= 1.0 + eps,
      s"Sampling fraction ($fraction) must be on interval [0, 1] without replacement")
  }

  override def output: Seq[Attribute] = child.output
}

/**
 * Returns a new logical plan that dedups input rows.
 */
case class Distinct(child: LogicalPlan) extends UnaryNode {
  override def maxRows: Option[Long] = child.maxRows
  override def output: Seq[Attribute] = child.output
}

/**
 * A base interface for [[RepartitionByExpression]] and [[Repartition]]
 */
abstract class RepartitionOperation extends UnaryNode {
  def shuffle: Boolean
  def numPartitions: Int
  override def output: Seq[Attribute] = child.output
}

/**
 * Returns a new RDD that has exactly `numPartitions` partitions. Differs from
 * [[RepartitionByExpression]] as this method is called directly by DataFrame's, because the user
 * asked for `coalesce` or `repartition`. [[RepartitionByExpression]] is used when the consumer
 * of the output requires some specific ordering or distribution of the data.
 */
case class Repartition(numPartitions: Int, shuffle: Boolean, child: LogicalPlan)
  extends RepartitionOperation {
  require(numPartitions > 0, s"Number of partitions ($numPartitions) must be positive.")
}

/**
 * This method repartitions data using [[Expression]]s into `optNumPartitions`, and receives
 * information about the number of partitions during execution. Used when a specific ordering or
 * distribution is expected by the consumer of the query result. Use [[Repartition]] for RDD-like
 * `coalesce` and `repartition`. If no `optNumPartitions` is given, by default it partitions data
 * into `numShufflePartitions` defined in `SQLConf`, and could be coalesced by AQE.
 */
case class RepartitionByExpression(
    partitionExpressions: Seq[Expression],
    child: LogicalPlan,
    optNumPartitions: Option[Int]) extends RepartitionOperation {

  val numPartitions = optNumPartitions.getOrElse(SQLConf.get.numShufflePartitions)
  require(numPartitions > 0, s"Number of partitions ($numPartitions) must be positive.")

  val partitioning: Partitioning = {
    val (sortOrder, nonSortOrder) = partitionExpressions.partition(_.isInstanceOf[SortOrder])

    require(sortOrder.isEmpty || nonSortOrder.isEmpty,
      s"${getClass.getSimpleName} expects that either all its `partitionExpressions` are of type " +
        "`SortOrder`, which means `RangePartitioning`, or none of them are `SortOrder`, which " +
        "means `HashPartitioning`. In this case we have:" +
      s"""
         |SortOrder: $sortOrder
         |NonSortOrder: $nonSortOrder
       """.stripMargin)

    if (sortOrder.nonEmpty) {
      RangePartitioning(sortOrder.map(_.asInstanceOf[SortOrder]), numPartitions)
    } else if (nonSortOrder.nonEmpty) {
      HashPartitioning(nonSortOrder, numPartitions)
    } else {
      RoundRobinPartitioning(numPartitions)
    }
  }

  override def maxRows: Option[Long] = child.maxRows
  override def shuffle: Boolean = true
}

object RepartitionByExpression {
  def apply(
      partitionExpressions: Seq[Expression],
      child: LogicalPlan,
      numPartitions: Int): RepartitionByExpression = {
    RepartitionByExpression(partitionExpressions, child, Some(numPartitions))
  }
}

/**
 * A relation with one row. This is used in "SELECT ..." without a from clause.
 */
case class OneRowRelation() extends LeafNode {
  override def maxRows: Option[Long] = Some(1)
  override def output: Seq[Attribute] = Nil
  override def computeStats(): Statistics = Statistics(sizeInBytes = 1)

  /** [[org.apache.spark.sql.catalyst.trees.TreeNode.makeCopy()]] does not support 0-arg ctor. */
  override def makeCopy(newArgs: Array[AnyRef]): OneRowRelation = {
    val newCopy = OneRowRelation()
    newCopy.copyTagsFrom(this)
    newCopy
  }
}

/** A logical plan for `dropDuplicates`. */
case class Deduplicate(
    keys: Seq[Attribute],
    child: LogicalPlan) extends UnaryNode {

  override def output: Seq[Attribute] = child.output
}

/**
 * A trait to represent the commands that support subqueries.
 * This is used to allow such commands in the subquery-related checks.
 */
trait SupportsSubquery extends LogicalPlan

/**
 * Collect arbitrary (named) metrics from a dataset. As soon as the query reaches a completion
 * point (batch query completes or streaming query epoch completes) an event is emitted on the
 * driver which can be observed by attaching a listener to the spark session. The metrics are named
 * so we can collect metrics at multiple places in a single dataset.
 *
 * This node behaves like a global aggregate. All the metrics collected must be aggregate functions
 * or be literals.
 */
case class CollectMetrics(
    name: String,
    metrics: Seq[NamedExpression],
    child: LogicalPlan)
  extends UnaryNode {

  override lazy val resolved: Boolean = {
    name.nonEmpty && metrics.nonEmpty && metrics.forall(_.resolved) && childrenResolved
  }

  override def output: Seq[Attribute] = child.output
}
