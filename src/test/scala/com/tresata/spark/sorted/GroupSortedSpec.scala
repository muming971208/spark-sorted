package com.tresata.spark.sorted

import scala.collection.mutable.ArrayBuffer
import org.scalatest.FunSpec
import org.scalatest.prop.Checkers

case class TimeValue(time: Int, value: Double)

class GroupSortedSpec extends FunSpec with Checkers {
  lazy val sc = SparkSuite.sc

  import PairRDDFunctions._

  def validGroupSorted[K, V](x: GroupSorted[K, V]): Boolean = {
    val keyValueOrdering = x.valueOrdering
      .map(Ordering.Tuple2(x.keyOrdering, _))
      .getOrElse(Ordering.by[(K, V), K](_._1)(x.keyOrdering))

    x.partitioner.map{ partitioner =>
      partitioner.numPartitions == x.partitions.size && // number of partitions must line up
        x.glom.collect.toList.map(_.toList).zipWithIndex.forall{ case (kvs, i) =>
          kvs.forall{ case (k, v) =>
            partitioner.getPartition(k) == i // keys must be in expected partition
          } &&
            kvs.sliding(2).forall{
              case List(kv1) => true
              case List(kv1, kv2) => keyValueOrdering.compare(kv1, kv2) <= 0 // key-value pairs must be ordered as expected
            }
        }
    }.getOrElse(false) // must have a partitioner
  }

  describe("PairRDDFunctions") {
    it("should produce correct group-sorted rdds") {
      // for strings the sorting by hash code is not the same as the natural ordering, so prefer strings for testing
      check{ (l: List[(String, String)], sortValues: Boolean) =>
        val rdd = sc.parallelize(l)
          .groupSort(2, if (sortValues) Some(Ordering.String) else None)
        validGroupSorted(rdd) && rdd.collect.toList.sorted == l.sorted
      }
    }

    it("should produce correct group-sorted rdds with aggregator") {
      // for strings the sorting by hash code is not the same as the natural ordering, so prefer strings for testing
      check{ (l: List[(String, Int)]) =>
        val rdd = sc.parallelize(l)
          .groupSort(2, { (v1: Int, v2: Int) => v1 + v2 })
        validGroupSorted(rdd) &&
        rdd.collect.toList.sorted == l.groupBy(_._1).mapValues(_.map(_._2).sum).toList.sorted
      }
    }
  }

  describe("GroupSorted") {
    it("should mapStreamByKey without value ordering") {
      val rdd = sc.parallelize(List(("a", 1), ("b", 10), ("a", 3), ("b", 1), ("c", 5)))
      val sets = rdd.groupSort(2).mapStreamByKey(iter => Iterator(iter.toSet)) // very contrived...
      assert(validGroupSorted(sets))
      assert(sets.collect.toMap ===  Map("a" -> Set(1, 3), "b" -> Set(1, 10), "c" -> Set(5)))
    }

    it("should mapStreamByKey with value ordering") {
      val rdd = sc.parallelize(List(("a", 1), ("b", 10), ("a", 3), ("b", 1), ("c", 5)))
      val withMax = rdd.groupSort(2, Ordering.Int.reverse).mapStreamByKey{ iter =>
        val buffered = iter.buffered
        val max = buffered.head
        buffered.map(_ => max)
      }
      assert(validGroupSorted(withMax))
      assert(withMax.collect.toList.groupBy(identity).mapValues(_.size) ===  Map(("a", 3) -> 2, ("b", 10) -> 2, ("c", 5) -> 1))
    }

    it("should mapStreamByKey with a mutable context and value ordering") {
      val rdd = sc.parallelize(List(("a", 1), ("b", 10), ("a", 3), ("b", 1), ("c", 5)))
      val withMax = rdd.groupSort(2, Ordering.Int.reverse).mapStreamByKey{ () => ArrayBuffer[Int]() }{ (buffer, iter) =>
        buffer.clear // i hope this preserves the underlying array otherwise there is no point really in re-using it
        buffer ++= iter
        val max = buffer.head
        buffer.map(_ => max)
      }
      assert(validGroupSorted(withMax))
      assert(withMax.collect.toList.groupBy(identity).mapValues(_.size) ===  Map(("a", 3) -> 2, ("b", 10) -> 2, ("c", 5) -> 1))
    }

    it("should foldLeftByKey without value ordering") {
      val rdd = sc.parallelize(List(("c", "x"), ("a", "b"), ("a", "c"), ("b", "e"), ("b", "d")))
      val sets = rdd.groupSort(2).foldLeftByKey(Set.empty[String]){ case (set, str) => set + str }
      assert(validGroupSorted(sets))
      assert(sets.collect.toMap === Map("a" -> Set("b", "c"), "b" -> Set("d", "e"), "c" -> Set("x")))
    }

    it("should foldLeftByKey with value ordering") {
      val ord = Ordering.by[TimeValue, Int](_.time)
      val tseries = sc.parallelize(List(
        (5, TimeValue(2, 0.5)), (1, TimeValue(1, 1.2)), (5, TimeValue(1, 1.0)),
        (1, TimeValue(2, 2.0)), (1, TimeValue(3, 3.0))
      ))
      val emas = tseries.groupSort(2, ord).foldLeftByKey(0.0){ case (acc, TimeValue(time, value)) => 0.8 * acc + 0.2 * value }
      assert(validGroupSorted(emas))
      assert(emas.collect.toSet === Set((1, 1.0736), (5, 0.26)))
    }

    it("should reduceLeftByKey without value ordering") {
      val rdd = sc.parallelize(List(("c", Set("x")), ("a", Set("b")), ("a", Set("c")), ("b", Set("e")), ("b", Set("d"))))
      val sets = rdd.groupSort(2).reduceLeftByKey { _ ++ _ }
      assert(validGroupSorted(sets))
      assert(sets.collect.toMap === Map("a" -> Set("b", "c"), "b" -> Set("d", "e"), "c" -> Set("x")))
    }

    it("should reduceLeftByKey with value ordering") {
      val rdd = sc.parallelize(List(("c", "x"), ("a", "b"), ("a", "c"), ("b", "e"), ("b", "d")))
      val sets = rdd.groupSort(2, Ordering.String).reduceLeftByKey { _ + _ }
      assert(validGroupSorted(sets))
      assert(sets.collect.toMap === Map("a" -> "bc", "b" -> "de", "c" -> "x"))
    }

    it("should mapStreamByKey with value ordering while not exhausting iterators") {
      val rdd = sc.parallelize(List(("a", 1), ("b", 10), ("a", 3), ("b", 1), ("c", 5)))
      val withMax = rdd.groupSort(2, Ordering.Int.reverse).mapStreamByKey{ iter =>
        Iterator(iter.next())
      }
      assert(validGroupSorted(withMax))
      assert(withMax.collect.toSet ===  Set(("a", 3), ("b", 10), ("c", 5)))
    }

    it("should mapStreamByKey if some keys have no output") {
      // see https://github.com/tresata/spark-sorted/issues/5
      val rdd = sc.parallelize(List(("a", 1), ("c", 10), ("a", 3), ("c", 1), ("b", 5)))
      val filtered = rdd.groupSort(2, Ordering.Int.reverse).mapStreamByKey(_.filter(_ < 5))
      assert(validGroupSorted(filtered))
      assert(filtered.collect.toSet ===  Set(("a", 1), ("a", 3), ("c", 1)))
    }

    it("should foldLeftByKey with a mutable accumulator") {
      import scala.collection.mutable.HashSet
      val rdd = sc.parallelize(List(("c", "x"), ("a", "b"), ("a", "c"), ("b", "e"), ("b", "d")))
      val sets = rdd.groupSort(2).foldLeftByKey(new HashSet[String]){ case (set, str) => set.add(str); set }
      assert(validGroupSorted(sets))
      assert(sets.collect.toMap === Map("a" -> Set("b", "c"), "b" -> Set("d", "e"), "c" -> Set("x")))
    }

    it("should scanLeftByKey with value ordering") {
      val rdd = sc.parallelize(List(("c", "x"), ("a", "b"), ("a", "c"), ("b", "e"), ("b", "d")))
      val sets = rdd.groupSort(2, Ordering.String).scanLeftByKey(Set.empty[String]){ case (set, str) => set + str }
      assert(validGroupSorted(sets))
      assert(sets.collect.toSet === Set(
        ("a", Set()),
        ("a", Set("b")),
        ("a", Set("b", "c")),
        ("b", Set()),
        ("b", Set("d")),
        ("b", Set("d", "e")),
        ("c", Set()),
        ("c", Set("x"))
      ))
    }

    it("should mapStreamByKey with value ordering for randomly generated datasets and take operations") {
      check{ (l: List[(Int, Int)]) =>
        val nTake: Int => Int = i => i % 10

        val rdd = sc.parallelize(l)
          .groupSort(2, Ordering.Int)
          .mapStreamByKey{ iter =>
            val biter = iter.buffered
            biter.take(nTake(biter.head))
          }
        val output = rdd.collect.toSet
        val check = l
          .groupBy(_._1).mapValues(_.map(_._2).sorted).toList
          .flatMap{ case (k, vs) => vs.take(nTake(vs.head)).map((k, _)) }
          .toSet
        validGroupSorted(rdd) && check === output
      }
    }

    it("should reduceLeftByKey without value ordering for randomly generated datasets") {
      check{ (l: List[(Int, Int)]) =>
        val rdd = sc.parallelize(l)
          .groupSort(2)
          .reduceLeftByKey(math.min)
        val output = rdd.collect.toSet
        val check = l
          .groupBy(_._1).mapValues(_.map(_._2)).toList
          .map{ case (k, vs) => (k, vs.min) }
          .toSet
        validGroupSorted(rdd) && check === output
      }
    }

    it("should chain operations") {
      val rdd = sc.parallelize(List(("a", 1), ("b", 10), ("a", 3), ("b", 1), ("c", 5)))
      val withMax = rdd.groupSort(2, Ordering.Int.reverse).mapValues(_ + 1).mapStreamByKey{ iter =>
        val buffered = iter.buffered
        val max = buffered.head
        buffered.map(_ => max)
      }
      assert(validGroupSorted(withMax))
      assert(withMax.collect.toList.groupBy(identity).mapValues(_.size) ===  Map(("a", 4) -> 2, ("b", 11) -> 2, ("c", 6) -> 1))
    }

    it("should mergeJoinInner") {
      val x = sc.parallelize((1 to 11).map(i => (i.toString, i.toString))).groupSort(1)
      val y = sc.parallelize((10 to 11).map(i => (i.toString, i.toString))).groupSort(1)
      val z = x.mergeJoinInner(y)
      assert(validGroupSorted(z))
      assert(z.collect.toSet === Set(("10", ("10", "10")), ("11", ("11", "11"))))
    }

    it("should mergeJoinInner with complex implicit ordering") {
      val x = sc.parallelize((1 to 11)).map(i => ((i, i), i)).groupSort(1)
      val y = sc.parallelize((10 to 11)).map(i => ((i, i), i)).groupSort(1)
      val z = x.mergeJoinInner(y)
      assert(validGroupSorted(z))
      assert(z.collect.toSet === Set(((10, 10), (10, 10)), ((11, 11), (11, 11)))) 
    }

    it("should mergeJoin for randomly generated datasets") {
      check{ (a: List[(String, String)], b: List[(String, String)], sortValuesA: Boolean, sortValuesB: Boolean) =>
        val check = {
          val aMap = a.groupBy(_._1).mapValues(_.map(_._2))
          val bMap = b.groupBy(_._1).mapValues(_.map(_._2))
          (aMap.keySet ++ bMap.keySet).toList.sorted.flatMap{ k =>
            (k, aMap.get(k), bMap.get(k)) match {
              case (k, Some(lv1), Some(lv2)) =>
                for (v1 <- lv1; v2 <- lv2) yield (k, (Some(v1), Some(v2)))
              case (k, Some(lv1), None) =>
                lv1.map(v1 => (k, (Some(v1), None)))
              case (k, None, Some(lv2)) =>
                lv2.map(v2 => (k, (None, Some(v2))))
              case (k, None, None) =>
                sys.error("should never happen")
            }
          }
        }

        val left = sc.parallelize(a).groupSort(2, if (sortValuesA) Some(Ordering.String) else None)
        val right = sc.parallelize(b).groupSort(2, if (sortValuesB) Some(Ordering.String) else None)

        val resultFullOuter = left.mergeJoin(right)
        val resultInner = left.mergeJoinInner(right)
        val resultLeftOuter = left.mergeJoinLeftOuter(right)
        val resultRightOuter = left.mergeJoinRightOuter(right)

        validGroupSorted(resultFullOuter) &&
          resultFullOuter.collect.toList.sorted === check.sorted &&
          validGroupSorted(resultInner) &&
          resultInner.collect.toList.sorted === check.collect{ case (k, (Some(v), Some(w))) => (k, (v, w)) }.sorted &&
          validGroupSorted(resultLeftOuter) &&
          resultLeftOuter.collect.toList.sorted === check.collect{ case (k, (Some(v), maybeW)) => (k, (v, maybeW)) }.sorted &&
          validGroupSorted(resultRightOuter) &&
          resultRightOuter.collect.toList.sorted === check.collect{ case (k, (maybeV, Some(w))) => (k, (maybeV, w)) }.sorted
      }
    }

    it("should mergeUnion randomly generated datasets") {
      check{ (a: List[(String, String)], b: List[(String, String)], sortValuesA: Boolean, sortValuesB: Boolean) =>
        val left = sc.parallelize(a).groupSort(2, if (sortValuesA) Some(Ordering.String) else None)
        val right = sc.parallelize(b).groupSort(2, if (sortValuesB) Some(Ordering.String) else None)
        val result = left.mergeUnion(right)
        val check = sc.parallelize(a ++ b).groupSort(2, None)

        validGroupSorted(result) && check.collect.toList.sorted === result.collect.toList.sorted
      }
    }
  }
}
