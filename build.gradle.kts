plugins {
	kotlin("jvm") version "1.9.25"
	kotlin("plugin.spring") version "1.9.25"
	id("org.springframework.boot") version "3.3.5"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.didimlog"
version = "0.0.1-SNAPSHOT"
description = "Step by step algorithm log"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(17)
	}
}

repositories {
	mavenCentral()
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

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}

// 단위 테스트 태스크: 파일명 패턴 **/*Test.kt 포함, **/*IntegrationTest.kt 제외
// DB나 Spring Context 없이 빠르게 실행되는 테스트만 수행
// @SpringBootTest를 사용하는 테스트는 통합 테스트로 간주하여 제외
tasks.named<Test>("test") {
	exclude("**/*IntegrationTest.class")
	exclude("**/*IT.class")
	exclude("**/DidimLogApplicationTests.class")
	exclude("**/DidimLogApplicationIntegrationTests.class")
	exclude("**/SecurityIntegrationTest.class")
	exclude("**/StudyIntegrationTest.class")
	exclude("**/RetrospectiveSearchIntegrationTest.class")
	exclude("**/JwtTokenProviderIntegrationTest.class")
}

// 통합 테스트 태스크: 파일명 패턴 **/*IntegrationTest.kt 또는 **/*IT.kt만 포함
// @SpringBootTest 등 무거운 의존성을 가진 테스트 수행
// 참고: 앞으로 통합 테스트 파일명은 반드시 IntegrationTest 또는 IT로 끝나야 합니다.
tasks.register<Test>("integrationTest") {
	group = "verification"
	description = "Runs integration tests"
	useJUnitPlatform()
	include("**/*IntegrationTest.class")
	include("**/*IT.class")
	include("**/DidimLogApplicationTests.class")
	include("**/DidimLogApplicationIntegrationTests.class")
	include("**/SecurityIntegrationTest.class")
	include("**/StudyIntegrationTest.class")
	include("**/RetrospectiveSearchIntegrationTest.class")
	include("**/JwtTokenProviderIntegrationTest.class")
	
	// 통합 테스트는 단위 테스트가 완료된 후 실행
	mustRunAfter("test")
}

