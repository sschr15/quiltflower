package pkg

import kotlin.properties.ReadWriteProperty

interface Component

fun <T> Component.getFromComponent(): ReadWriteProperty<Component, T> = TODO()

class TestDelegate : Component {
  val test by getFromComponent<String>()

  val someExpensiveValue by lazy {
    println("Computing someExpensiveValue")
    42
  }
}