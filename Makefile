JAVA_HOME := $(shell sh scripts/find-jdk17.sh)
export JAVA_HOME

MVN := sh ./mini-mvn

.PHONY: all build test doctor install clean help

all: build

help:
	@echo "make build    compile the engine from source"
	@echo "make test     run the JUnit smoke suite"
	@echo "make doctor   report JDK, tools, dependencies and state"
	@echo "make install  build, then stage mini-mvn.jar under dist/bin"
	@echo "make clean    remove target/ and dist/"

build:
	$(MVN) build

test:
	$(MVN) test

doctor:
	$(MVN) doctor

install:
	$(MVN) install

clean:
	$(MVN) clean
	rm -rf target dist
