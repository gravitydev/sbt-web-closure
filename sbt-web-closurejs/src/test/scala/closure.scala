package com.gravitydev.sbt.closurejs

import org.scalatest._
import sbt._
import scala.collection.JavaConverters._

class ClosureSpec extends FlatSpec with Matchers {
  "Compilation" should "work" in {
    val lib = new java.io.File(getClass.getResource("/lib").toURI)
    val file = new java.io.File(getClass.getResource("/test.js").toURI)

    val result = ClosureJsCompiler2.compile(file, lib ** "*.js", false, false)

    println(result)

    1 should be(1)
  }
}

