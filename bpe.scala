import java.io.{File, FileInputStream, FileOutputStream, BufferedOutputStream}
import scala.math.min
import scala.collection.mutable

var data: Array[Int] = Array.empty[Int]

def loadData(fname: String): Unit = {
  val file = new File(fname)
  val fileSize = file.length().toInt
  val byteArray = new Array[Byte](fileSize)

  val inputStream = new FileInputStream(file)
  inputStream.read(byteArray)
  inputStream.close()

  data = byteArray.map(_ & 0xFF)
}

class WalkIter {
  var pos = 0

  private def skipHoles(): Unit = {
    while (pos < data.length && data(pos) == 0) {
      pos += 1
    }
  }

  skipHoles()

  def next(): Option[Int] = {
    if (pos >= data.length) None
    else {
      val p = pos
      pos += 1
      skipHoles()
      Some(p)
    }
  }
}

class PairWalker {
  private val walker = new WalkIter
  private var p1: Option[Int] = walker.next()
  private var p2: Option[Int] = walker.next()

  def next(): Option[(Int, Int)] = {
    (p1, p2) match {
      case (Some(a), Some(b)) =>
        p1 = p2
        p2 = walker.next()
        Some((a, b))
      case _ => None
    }
  }

  def afterReplace(replacePos: Int): (Option[Int], Option[Int]) = {
    p1 = p2
    p2 = walker.next()
    var prevIdx: Option[Int] = None
    if (replacePos > 0) {
      var i = replacePos - 1
      while (data(i) == 0 && i > 0) { i -= 1 }
      if (data(i) != 0) { prevIdx = Some(i) }
    }
    (prevIdx, p1)
  }
}

case class VPair(value1: Int, value2: Int) extends Ordered[VPair] {
  def compare(that: VPair): Int = {
    if (value1 != that.value1) value1.compare(that.value1)
    else value2.compare(that.value2)
  }
}

def calcPairHisto(): mutable.Map[VPair, Int] = {
  val h = mutable.Map[VPair, Int]().withDefaultValue(0)
  def loop(pairs: PairWalker): Unit = {
    pairs.next() match {
      case Some((i, j)) =>
        val vp = VPair(data(i), data(j))
        h(vp) += 1
        loop(pairs)
      case None => // End of pairs, do nothing
    }
  }

  loop(new PairWalker)
  h
}

def mostFreqVal(h: mutable.Map[VPair, Int]): (VPair, Int, Int) = {
  var minTop = VPair(0,0)
  var maxN = 0
  var total = 0
  for ((k, n) <- h) {
    total += n
    if (n > maxN) {
      maxN = n
      minTop = k
    } else if (n == maxN && k < minTop) {
      minTop = k
    }
  }
  (minTop, maxN, total)
}

def replacePair(oldValuePair: VPair, newVal: Int, ph: mutable.Map[VPair, Int]): Unit = {
  def loop(pairs: PairWalker): Unit = {
    pairs.next() match {
      case Some((i, j)) =>
        val vp = VPair(data(i), data(j))
        if (vp == oldValuePair) {
          data(i) = newVal
          data(j) = 0
          val (prevIdxOpt, nextIdxOpt) = pairs.afterReplace(i)
          prevIdxOpt.foreach { prevIdx =>
            val leftVal = data(prevIdx)
            ph(VPair(leftVal, vp.value1)) -= 1
            ph(VPair(leftVal, newVal)) += 1
          }
          nextIdxOpt.foreach { nextIdx =>
            val rightVal = data(nextIdx)
            ph(VPair(vp.value2, rightVal)) -= 1
            ph(VPair(newVal, rightVal)) += 1
          }
        }
        loop(pairs)
      case None => // End of pairs, do nothing
    }
  }

  loop(new PairWalker)
}

var nextValue: Int = 256
var thesaurus: mutable.Map[Int, List[Byte]] = mutable.Map[Int, List[Byte]]()

def oneStep(step: Int, ph: mutable.Map[VPair, Int]): Boolean = {
  val (vp, n, total) = mostFreqVal(ph)
  if (n == 1) return true
  if (total.toDouble < data.length * 0.707) {
    data = data.filter(_ != 0)
  }
  if (step % 100 == 0) {
    println(s"Step $step: n=$n $vp -> $nextValue")
  }
  replacePair(vp, nextValue, ph)
  ph(vp) = 0
  thesaurus(nextValue) = thesaurus(vp.value1) ++ thesaurus(vp.value2)
  nextValue += 1
  false
}

def saveTokens(fname: String): Unit = {
  val chunkSize = 1000000
  val filePath = s"$fname.stok"
  val fos = new FileOutputStream(filePath)
  val bos = new BufferedOutputStream(fos)
  val h = mutable.Map[Int, Int]().withDefaultValue(0)

  for(x <- data) {
    if (x != 0) {
      bos.write(x & 0xFF)
      bos.write((x>>8) & 0xFF)
      h(x) += 1
    }
  }

  bos.close()
  fos.close()
}

def main(args: Array[String]): Unit = {
  val fname = if (args.nonEmpty) args(0) else "enw3"
  for (x <- 0 to 255) {
    thesaurus(x) = List(x.toByte)
  }

  loadData(fname)
  println(data.length)
  var ph = calcPairHisto()

  def loop(i: Int): Unit = {
    if (i <= 65000 && !oneStep(i, ph)) {
      if (i % 1000 == 0) {
        saveTokens(fname)
      }
      loop(i + 1)
    }
  }
  loop(1)
  saveTokens(fname)
}
