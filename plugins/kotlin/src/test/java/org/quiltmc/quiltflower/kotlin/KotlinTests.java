package org.quiltmc.quiltflower.kotlin;

import org.jetbrains.java.decompiler.DecompilerTestFixture;
import org.jetbrains.java.decompiler.SingleClassesTestBase;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;

import java.nio.file.Path;

import static org.jetbrains.java.decompiler.SingleClassesTestBase.TestDefinition.Version.KOTLIN;

public class KotlinTests extends SingleClassesTestBase {

  protected Path getClassFile(DecompilerTestFixture fixture, TestDefinition.Version version, String name) {
    Path reg = fixture.getTestDataDir().resolve("classes/" + version.directory + "/" + name + ".class");
    Path kt = fixture.getTestDataDir().resolve("classes/" + version.directory + "/" + name + "Kt.class");

    return reg.toFile().exists() ? reg : kt;
  }

  protected void registerAll() {
    registerSet("Entire Classpath", this::registerKotlinTests,
      IFernflowerPreferences.BYTECODE_SOURCE_MAPPING, "1",
      IFernflowerPreferences.DUMP_ORIGINAL_LINES, "1",
      IFernflowerPreferences.DUMP_EXCEPTION_ON_ERROR, "0",
      IFernflowerPreferences.IGNORE_INVALID_BYTECODE, "1",
      IFernflowerPreferences.REMOVE_SYNTHETIC, "0",
      IFernflowerPreferences.VERIFY_ANONYMOUS_CLASSES, "1"
    );
  }

  private void registerKotlinTests() {
    // TODO: Annotation classes decompile entirely incorrectly
    register(KOTLIN, "TestAnnotations");
    register(KOTLIN, "TestAnyType");
    register(KOTLIN, "TestBitwiseFunctions");
    register(KOTLIN, "TestClassDec");
    register(KOTLIN, "TestComparison");
    register(KOTLIN, "TestCompileTimeErrors");
    // TODO: lists, sets, etc. should be considered to be their Kotlin type, not their Java type
    register(KOTLIN, "TestConvertedK2JOps");
    // TODO: Does not decompile to data class
    register(KOTLIN, "TestDataClass");
    // TODO: Does not decompile to destructuring declaration
    register(KOTLIN, "TestDestructors");
    register(KOTLIN, "TestExtensionFun");
    register(KOTLIN, "TestForRange");
    register(KOTLIN, "TestFuncRef");
    register(KOTLIN, "TestFunVarargs");
    // TODO: Type projections are not Kotlin equivalents
    register(KOTLIN, "TestGenerics");
    register(KOTLIN, "TestIfRange");
    register(KOTLIN, "TestInfixFun");
    // TODO: Nothing, function types
    register(KOTLIN, "TestKotlinTypes");
    register(KOTLIN, "TestKt");
    register(KOTLIN, "TestLabeledJumps");
    // TODO: handle lambdas
    register(KOTLIN, "TestNonInlineLambda");
    register(KOTLIN, "TestNothingReturns");
    // TODO: Generics containing nullability (like List<String?>) lose the nullability
    register(KOTLIN, "TestNullable");
    register(KOTLIN, "TestNullableOperator");
    // TODO: Does not decompile to object literal
    // FIXME: @JvmStatic function fails to decompile at all
    register(KOTLIN, "TestObject");
    register(KOTLIN, "TestParams");
    register(KOTLIN, "TestPoorNames");
    // TODO: sealed becomes "open abstract"
    register(KOTLIN, "TestSealedHierarchy");
    register(KOTLIN, "TestShadowParam");
    register(KOTLIN, "TestSmartCasts");
    register(KOTLIN, "TestSynchronized");
    register(KOTLIN, "TestTailrecFunctions");
    // TODO: top-level constructs are wrongly put into a class and turned into instance methods
    register(KOTLIN, "TestTopLevelKt");
    register(KOTLIN, "TestTryCatchExpressions");
    register(KOTLIN, "TestTryFinallyExpressions");
    register(KOTLIN, "TestVars");
    // TODO: gets condensed into ternaries
    register(KOTLIN, "TestWhen");
    register(KOTLIN, "TestWhenBoolean");
    // TODO: x = x needs to not have the type attached
    register(KOTLIN, "TestWhenControlFlow");
  }
}
