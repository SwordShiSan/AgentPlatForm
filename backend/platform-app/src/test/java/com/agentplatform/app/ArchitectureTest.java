package com.agentplatform.app;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

class ArchitectureTest {
    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.agentplatform");

    @Test
    void modulesHaveNoCycles() {
        slices().matching("com.agentplatform.(*)..").should().beFreeOfCycles().check(classes);
    }

    @Test
    void foundationalModulesDependOnlyOnPureJavaAndTheirDeclaredPorts() {
        classes().that().resideInAPackage("com.agentplatform.types..").should().onlyDependOnClassesThat()
                .resideInAnyPackage("java..", "com.agentplatform.types..").check(classes);
        classes().that().resideInAPackage("com.agentplatform.domain..").should().onlyDependOnClassesThat()
                .resideInAnyPackage("java..", "com.agentplatform.types..", "com.agentplatform.domain..").check(classes);
        classes().that().resideInAPackage("com.agentplatform.api..").should().onlyDependOnClassesThat()
                .resideInAnyPackage("java..", "com.agentplatform.types..", "com.agentplatform.api..").check(classes);
    }

    @Test
    void enforceModuleDependencyDirections() {
        noClasses().that().resideInAPackage("..types..").should().dependOnClassesThat()
                .resideInAnyPackage("..domain..", "..api..", "..infrastructure..", "..trigger..", "..app..").check(classes);
        noClasses().that().resideInAPackage("..domain..").should().dependOnClassesThat()
                .resideInAnyPackage("..api..", "..infrastructure..", "..trigger..", "..app..",
                        "org.springframework..", "io.agentscope..", "reactor..", "jakarta.persistence..", "java.sql..", "javax.sql..").check(classes);
        noClasses().that().resideInAPackage("..api..").should().dependOnClassesThat()
                .resideInAnyPackage("..domain..", "..infrastructure..", "..trigger..", "..app..").check(classes);
        noClasses().that().resideInAPackage("..infrastructure..").should().dependOnClassesThat()
                .resideInAnyPackage("..api..", "..trigger..", "..app..").check(classes);
        noClasses().that().resideInAPackage("..trigger..").should().dependOnClassesThat()
                .resideInAnyPackage("..infrastructure..", "..app..").check(classes);
    }
}
