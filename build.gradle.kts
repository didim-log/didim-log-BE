plugins {
	kotlin("jvm") version "1.9.25"
	kotlin("plugin.spring") version "1.9.25"
	id("org.springframework.boot") version "3.3.5"
	id("io.spring.dependency-management") version "1.1.7"
	id("jacoco")
}

group = "com.didimlog"
version = "0.0.1-SNAPSHOT"
description = "Step by step algorithm log"

springBoot {
    mainClass.set("com.didimlog.DidimLogApplication")
}

val integrationTest by sourceSets.creating {
	kotlin.srcDir("src/integrationTest/kotlin")
	resources.srcDirs("src/integrationTest/resources", "src/test/resources")
	compileClasspath += sourceSets["main"].output + configurations["testRuntimeClasspath"]
	runtimeClasspath += output + compileClasspath
}

val jacocoCoreCoverageIncludes = listOf(
	"com/didimlog/application/admin/**",
	"com/didimlog/application/auth/**",
	"com/didimlog/application/dashboard/**",
	"com/didimlog/application/feedback/**",
	"com/didimlog/application/log/**",
	"com/didimlog/application/member/**",
	"com/didimlog/application/notice/**",
	"com/didimlog/application/problem/**",
	"com/didimlog/application/quote/**",
	"com/didimlog/application/ranking/**",
	"com/didimlog/application/recommendation/**",
	"com/didimlog/application/retrospective/**",
	"com/didimlog/application/storage/**",
	"com/didimlog/application/student/**",
	"com/didimlog/application/study/**",
	"com/didimlog/application/utils/**",
	"com/didimlog/domain/*.class",
	"com/didimlog/domain/*\\$*.class",
	"com/didimlog/domain/enums/**",
	"com/didimlog/domain/repository/**",
	"com/didimlog/domain/template/**",
	"com/didimlog/domain/validation/**",
	"com/didimlog/domain/valueobject/**",
	"com/didimlog/global/auth/**",
	"com/didimlog/global/config/**",
	"com/didimlog/global/config/security/**",
	"com/didimlog/global/system/**",
	"com/didimlog/global/util/**"
)

val jacocoCoreCoverageExcludes = listOf(
	"com/didimlog/application/problem/collector/**",
	"**/*Test*"
)

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(17)
	}
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-data-mongodb")
	implementation("org.springframework.boot:spring-boot-starter-data-redis")
	implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.3.0")
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	
	// JWT
	implementation("io.jsonwebtoken:jjwt-api:0.12.3")
	implementation("io.jsonwebtoken:jjwt-impl:0.12.3")
	implementation("io.jsonwebtoken:jjwt-jackson:0.12.3")
	
	// HTML Parsing
	implementation("org.jsoup:jsoup:1.17.2")
	
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.security:spring-security-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("io.mockk:mockk:1.13.12")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations["testImplementation"])
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations["testRuntimeOnly"])

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}

val integrationTestTask = tasks.register<Test>("integrationTest") {
	group = "verification"
	description = "Runs integration tests from src/integrationTest"
	useJUnitPlatform()
	testClassesDirs = integrationTest.output.classesDirs
	classpath = integrationTest.runtimeClasspath
	mustRunAfter("test")
}

tasks.named("check") {
	dependsOn(integrationTestTask)
}

jacoco {
	toolVersion = "0.8.12"
}

tasks.named<Test>("test") {
	maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
}

tasks.named<JacocoReport>("jacocoTestReport") {
	dependsOn("test")
	classDirectories.setFrom(
		files(
			sourceSets["main"].output.asFileTree.matching {
				include(jacocoCoreCoverageIncludes)
				exclude(jacocoCoreCoverageExcludes)
			}
		)
	)

	reports {
		xml.required.set(true)
		html.required.set(true)
		csv.required.set(false)
	}
}

tasks.register<JacocoReport>("jacocoIntegrationTestReport") {
	group = "verification"
	description = "Generates JaCoCo coverage report for integration tests"
	dependsOn(integrationTestTask)

	executionData(fileTree(layout.buildDirectory).include("jacoco/integrationTest.exec", "jacoco/integrationTest*.exec"))
	sourceSets(sourceSets["main"])
	classDirectories.setFrom(
		files(
			sourceSets["main"].output.asFileTree.matching {
				include(jacocoCoreCoverageIncludes)
				exclude(jacocoCoreCoverageExcludes)
			}
		)
	)

	reports {
		xml.required.set(true)
		html.required.set(true)
		csv.required.set(false)
	}
}

tasks.register<JacocoReport>("jacocoMergedReport") {
	group = "verification"
	description = "Generates merged JaCoCo coverage report for unit + integration tests"
	dependsOn("test", integrationTestTask)

	executionData(
		fileTree(layout.buildDirectory).include(
			"jacoco/test.exec",
			"jacoco/integrationTest.exec",
			"jacoco/*.exec"
		)
	)
	sourceSets(sourceSets["main"])
	classDirectories.setFrom(
		files(
			sourceSets["main"].output.asFileTree.matching {
				include(jacocoCoreCoverageIncludes)
				exclude(jacocoCoreCoverageExcludes)
			}
		)
	)

	reports {
		xml.required.set(true)
		html.required.set(true)
		csv.required.set(false)
	}
}
