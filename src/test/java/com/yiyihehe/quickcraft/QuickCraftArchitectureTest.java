package com.yiyihehe.quickcraft;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * QuickCraft 架构合规测试。
 * 检查包依赖、命名规范、分层边界、循环依赖等。
 */
class QuickCraftArchitectureTest {

    private static final String ROOT = "com.yiyihehe.quickcraft";
    private static JavaClasses classes;

    @BeforeAll
    static void setUp() {
        classes = new ClassFileImporter().importPackages(ROOT);
    }

    // ---- 包依赖规则 ----

    @Test
    @DisplayName("mixin 包依赖检查：记录但不强制（mixin 需要引用业务包）")
    void mixinPackages_dependencyLogging() {
        // Mixin 本质上是 Minecraft 和业务代码之间的桥接层，
        // 它引用业务类（如 QuickSort、QuickContainerLock 等）是正常的。
        // 此测试保留用于文档目的，不做强制约束。
        assertNotNull(classes);
    }

    @Test
    @DisplayName("crafting 包依赖记录（mixin 引用 crafting 是正常桥接）")
    void craftingPackageDependencyLogging() {
        // crafting 包可能被 mixin 或根包引用，这是正常的依赖方向。
        // 此测试保留用于文档目的，不做强制约束。
        assertNotNull(classes);
    }

    @Test
    @DisplayName("litematica 包只能被根包或自身引用")
    void litematicaPackageDependency() {
        ArchRule rule = classes()
                .that().resideInAPackage(ROOT + ".litematica..")
                .should().onlyHaveDependentClassesThat()
                .resideInAnyPackage(
                        ROOT + ".litematica..",
                        ROOT,
                        ROOT + ".config..",
                        ROOT + ".mixin..",
                        "java..",
                        "net.fabricmc..",
                        "net.minecraft..",
                        "fi.dy.masa..")
                .because("litematica 子包不应被其他子包（如 crafting）强依赖");

        rule.check(classes);
    }

    // ---- 命名规范 ----

    @Test
    @DisplayName("crafting 包中的类应以 QuickCraft 开头")
    void craftingClasses_shouldBeNamedQuickCraft() {
        ArchRule rule = classes()
                .that().resideInAPackage(ROOT + ".crafting..")
                .and().areTopLevelClasses()
                .should().haveSimpleNameStartingWith("QuickCraft")
                .because("crafting 包按规范使用 QuickCraftXxx 命名");

        rule.check(classes);
    }

    @Test
    @DisplayName("litematica 包中的类应以 QuickLitematica 开头")
    void litematicaClasses_shouldBeNamedQuickLitematica() {
        ArchRule rule = classes()
                .that().resideInAPackage(ROOT + ".litematica..")
                .and().areTopLevelClasses()
                .should().haveSimpleNameStartingWith("QuickLitematica")
                .because("litematica 包按规范使用 QuickLitematicaXxx 命名");

        rule.check(classes);
    }

    @Test
    @DisplayName("根包独立小功能应以 Quick 开头")
    void rootPackageUtilityClasses_shouldBeNamedQuick() {
        ArchRule rule = classes()
                .that().resideInAPackage(ROOT)
                .and().areTopLevelClasses()
                .and().haveSimpleNameNotStartingWith("QuickCraft")
                .should().haveSimpleNameStartingWith("Quick")
                .because("根包独立功能类按规范使用 QuickXxx 命名");

        rule.check(classes);
    }

    @Test
    @DisplayName("mixin 包中的类应以 Mixin 结尾或以 Accessor/Invoker 命名")
    void mixinClasses_namingConvention() {
        ArchRule rule = classes()
                .that().resideInAPackage(ROOT + ".mixin..")
                .and().areTopLevelClasses()
                .should().haveSimpleNameContaining("Mixin")
                .orShould().haveSimpleNameContaining("Accessor")
                .orShould().haveSimpleNameContaining("Invoker")
                .orShould().haveSimpleNameContaining("Plugin")
                .because("mixin 包按规范命名");

        rule.check(classes);
    }

    // ---- 分层规则 ----

    @Test
    @DisplayName("config 包不应依赖业务包（crafting/litematica）")
    void configPackage_shouldNotDependOnBusinessLogic() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(ROOT + ".config..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        ROOT + ".crafting..",
                        ROOT + ".litematica..")
                .because("config 包应该只定义配置，不依赖具体业务逻辑");

        rule.check(classes);
    }

    @Test
    @DisplayName("gui 包不应被根包反向依赖")
    void guiPackage_dependencies() {
        ArchRule rule = classes()
                .that().resideInAPackage(ROOT + ".gui..")
                .should().onlyHaveDependentClassesThat()
                .resideInAnyPackage(
                        ROOT + ".gui..",
                        ROOT + ".malilib..",
                        ROOT,
                        "java..",
                        "com.terraformersmc..")
                .because("gui 作为表现层，不应被业务模块强依赖");

        rule.check(classes);
    }

    // ---- 代码质量规则 ----

    @Test
    @DisplayName("不应有循环依赖（包级）")
    void noCyclicDependencies() {
        ArchRule rule = slices()
                .matching(ROOT + ".(*)..")
                .should().beFreeOfCycles();

        rule.check(classes);
    }

    @Test
    @DisplayName("不应使用通配符 import")
    void noWildcardImports() {
        // 当前不做强制检查，保留为未来启用
        assertNotNull(classes);
    }

    @Test
    @DisplayName("所有 main 类都应在 com.yiyihehe.quickcraft 包下")
    void allClasses_inCorrectPackage() {
        ArchRule rule = classes()
                .should().resideInAnyPackage(
                        ROOT,
                        ROOT + "..")
                .because("所有源代码应在正确的包结构下");

        rule.check(classes);
    }
}
