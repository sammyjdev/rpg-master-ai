package com.rpgmaster.app.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.rpgmaster.app.RpgMasterApplication;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Enforces the hexagonal architecture boundary documented in ADR-004.
 *
 * <p>The application layer ({@code com.rpgmaster.app.application..}) orchestrates
 * use cases and must remain independent from concrete infrastructure libraries:
 * Spring AI, Qdrant client, PDFBox, Spring Data JPA and {@code jakarta.persistence}
 * are all framework-level concerns that must live behind ports under
 * {@code com.rpgmaster.app.application.port..} and only be referenced from
 * {@code com.rpgmaster.app.adapter.outbound..}.
 */
@AnalyzeClasses(
        packagesOf = RpgMasterApplication.class,
        importOptions = {ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeJars.class})
public class HexagonalBoundaryTest {

    @ArchTest
    static final ArchRule application_layer_must_not_depend_on_spring_ai =
            noClasses()
                    .that().resideInAPackage("..application..")
                    .should().dependOnClassesThat().resideInAPackage("org.springframework.ai..")
                    .because("ADR-004: application layer talks to LLM/embeddings via ports, "
                            + "Spring AI types live in adapter.outbound only");

    @ArchTest
    static final ArchRule application_layer_must_not_depend_on_qdrant =
            noClasses()
                    .that().resideInAPackage("..application..")
                    .should().dependOnClassesThat().resideInAPackage("io.qdrant..")
                    .because("ADR-004: vector store access is hidden behind VectorStorePort");

    @ArchTest
    static final ArchRule application_layer_must_not_depend_on_pdfbox =
            noClasses()
                    .that().resideInAPackage("..application..")
                    .should().dependOnClassesThat().resideInAPackage("org.apache.pdfbox..")
                    .because("ADR-004: PDF parsing belongs to ChunkingPort adapters");

    @ArchTest
    static final ArchRule application_layer_must_not_depend_on_jpa =
            noClasses()
                    .that().resideInAPackage("..application..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("org.springframework.data.jpa..", "jakarta.persistence..")
                    .because("ADR-004: persistence concerns live behind DocumentRepository port");

    /**
     * Positive rule — confirms the application layer is wired through its port
     * sub-package. Guards against a future refactor that would route use cases
     * directly to adapter implementations and bypass the boundary.
     */
    @ArchTest
    static final ArchRule application_use_cases_depend_on_ports =
            classes()
                    .that().resideInAPackage("com.rpgmaster.app.application")
                    .and().haveSimpleNameEndingWith("UseCase")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.rpgmaster.app.application.port..")
                    .because("ADR-004: use cases orchestrate via ports, not concrete adapters");

}
