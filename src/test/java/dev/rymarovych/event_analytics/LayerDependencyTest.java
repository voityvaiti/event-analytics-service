package dev.rymarovych.event_analytics;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Holds the layers apart: web and consumer are the entry points, service sits behind them, and
 * persistence and messaging are what service reaches out through. Domain depends on none of them.
 */
@AnalyzeClasses(
    packages = "dev.rymarovych.event_analytics",
    importOptions = ImportOption.DoNotIncludeTests.class)
class LayerDependencyTest {

  private static final String ROOT = "dev.rymarovych.event_analytics";

  @ArchTest
  static final ArchRule packagesAreFreeOfCycles =
      slices().matching(ROOT + ".(*)..").should().beFreeOfCycles();

  @ArchTest
  static final ArchRule domainDependsOnNoLayer =
      noClasses()
          .that()
          .resideInAPackage(ROOT + ".domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              layers("web", "consumer", "service", "persistence", "messaging", "config"));

  @ArchTest
  static final ArchRule persistenceDependsOnlyOnDomain =
      noClasses()
          .that()
          .resideInAPackage(ROOT + ".persistence..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(layers("web", "consumer", "service", "messaging", "config"));

  @ArchTest
  static final ArchRule messagingDependsOnlyOnDomain =
      noClasses()
          .that()
          .resideInAPackage(ROOT + ".messaging..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(layers("web", "consumer", "service", "persistence", "config"));

  @ArchTest
  static final ArchRule serviceDependsOnNoEntryPoint =
      noClasses()
          .that()
          .resideInAPackage(ROOT + ".service..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(layers("web", "consumer"));

  @ArchTest
  static final ArchRule consumerReachesStorageOnlyThroughService =
      noClasses()
          .that()
          .resideInAPackage(ROOT + ".consumer..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(layers("web", "persistence"));

  @ArchTest
  static final ArchRule webReachesStorageAndBrokerOnlyThroughService =
      noClasses()
          .that()
          .resideInAPackage(ROOT + ".web..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(layers("persistence", "messaging"));

  private static String[] layers(String... names) {
    String[] packages = new String[names.length];
    for (int index = 0; index < names.length; index++) {
      packages[index] = ROOT + "." + names[index] + "..";
    }
    return packages;
  }
}
