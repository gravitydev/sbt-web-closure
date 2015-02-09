package com.gravitydev.sbt.closurejs

import org.scalatest._
import sbt._
import scala.collection.JavaConverters._

class ClosureSpec extends FlatSpec with Matchers {
  "Compilation" should "work" in {
    val lib = new java.io.File(getClass.getResource("/lib").toURI)
    val inputFile = new java.io.File(getClass.getResource("/test.js").toURI)
    val closureLib = file("/home/alvaro/workspace/gravity/closure-library")

    println((closureLib ** "*.js").get)

    val result = ClosureJsCompiler.compile(inputFile, (closureLib ** "*.js").get ++ (lib ** "*.js").get, closureLib, Nil, false, false)

    println(result)

    1 should be(1)
  }
}

