OS_NAME := $(shell uname)
ifeq ($(OS_NAME), Darwin)
OPEN := open
else
OPEN := xdg-open
endif

qa: analyze test

analyze:
	@./gradlew spotlessCheck compileJava compileTestJava javadoc

format:
	@./gradlew spotlessApply

test:
	@./gradlew test jacocoTestCoverageVerification

coverage:
	@./gradlew test jacocoTestReport
	@$(OPEN) ./eventsourcingdb/build/reports/jacoco/test/html/index.html
	@$(OPEN) ./eventsourcingdb-testcontainers/build/reports/jacoco/test/html/index.html

.PHONY: analyze coverage format qa test
