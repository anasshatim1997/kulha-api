package ma.kulha.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "ma.kulha", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundariesTest {

    private static final List<String> MODULES = List.of(
            "identity", "merchant", "offer", "reservation",
            "notification", "payment", "admin");

    // The domain layer must stay free of Spring and JPA.
    @ArchTest
    static final ArchRule domain_has_no_framework_dependencies =
            noClasses().that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework..", "jakarta.persistence..")
                    .allowEmptyShould(true);

    // A module never reaches into another module's internals.
    @ArchTest
    static void modules_do_not_use_each_others_internals(JavaClasses classes) {
        for (String module : MODULES) {
            String[] forbidden = MODULES.stream()
                    .filter(other -> !other.equals(module))
                    .flatMap(other -> java.util.stream.Stream.of(
                            "ma.kulha." + other + ".domain.model..",
                            "ma.kulha." + other + ".domain.repository..",
                            "ma.kulha." + other + ".infrastructure..",
                            "ma.kulha." + other + ".interfaces.."))
                    .toArray(String[]::new);

            noClasses().that().resideInAPackage("ma.kulha." + module + "..")
                    .should().dependOnClassesThat().resideInAnyPackage(forbidden)
                    .allowEmptyShould(true)
                    .check(classes);
        }
    }
}