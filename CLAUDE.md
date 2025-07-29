# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Joern is a platform for analyzing source code, bytecode, and binary executables using Code Property Graphs (CPGs). It generates graph representations of code for cross-language static analysis and vulnerability discovery. The project is written in Scala 3 and uses SBT as the build system.

## Development Commands

### Build System
- **Primary build tool**: SBT (Scala Build Tool)
- **Build the project**: `sbt compile`
- **Run tests**: `sbt test`
- **Format code**: `sbt scalafmt Test/scalafmt` (required before submitting PRs)
- **Create distribution**: `sbt createDistribution`
- **Stage for local testing**: `sbt stage`

### QueryDB Development
- **Build and test QueryDB**: `sbt stage && ./querydb-install.sh && ./joern-scan --list-query-names`
- **Install QueryDB**: `./querydb-install.sh`

### Testing Scripts
- **Smoke tests**: `./tests/smoke-test.sh`
- **Frontend tests**: `./tests/frontends-tests.sh`
- **QueryDB tests**: `./tests/querydb-test.sh`

## Architecture Overview

### Core Components

**joern-cli**: Main CLI interface that orchestrates all functionality
- Entry point through `JoernConsole`
- Workspace and project management
- Integration point for all frontends and analysis engines

**semanticcpg**: Query language and traversal API
- Domain-specific language for querying CPGs
- Provides traversal starting points like `cpg.method`, `cpg.call`
- Fluent API with method chaining for complex queries

**dataflowengineoss**: Dataflow analysis engine
- Implements reaching definitions and dataflow analysis
- Powers data flow queries from sources to sinks

**console**: Interactive REPL framework
- Base console infrastructure for workspace management
- Handles CPG loading, overlay application, and query execution

### Frontend Architecture

All language frontends (*2cpg) follow a consistent pattern:
1. **x2cpg**: Common frontend infrastructure and shared passes
2. **Individual frontends** (c2cpg, gosrc2cpg, javasrc2cpg, etc.): Language-specific parsing and CPG generation
3. **Pattern**: Main → Frontend → AstCreator → CPG generation

### CPG Processing Pipeline

1. **Frontend CPG**: Raw CPG from source parsing
2. **Base Layer**: AST linking, method stubs, namespace resolution
3. **Control Flow**: CFG edges and dominator information
4. **Type Relations**: Type hierarchy and alias linking
5. **Call Graph**: Method call resolution
6. **Data Flow**: Reaching definitions and dataflow edges

### Key Architectural Patterns

- **Layered Enhancement**: CPGs built incrementally through overlay application
- **Language-Agnostic Core**: Query language works across all supported languages
- **Pass-Based Processing**: Analysis structured as discrete passes over the CPG
- **Unified Graph Model**: AST, CFG, call graph unified in single queryable structure

## Development Workflow

### Setting Up Development Environment

**IntelliJ IDEA**:
1. Install Scala Plugin
2. Open SBT in terminal: `sbt compile` (keep running)
3. Open project as BSP project (not SBT project)

**VSCode**:
1. Install Docker and `ms-vscode-remote.remote-containers` plugin
2. Open in container when prompted
3. Import build via Metals sidebar

### Working with Frontends

Each frontend has its own module under `joern-cli/frontends/`:
- Build specific frontend: `sbt <frontendName>/compile`
- Test specific frontend: `sbt <frontendName>/test`
- Common patterns defined in `x2cpg` module

### Code Style and Contributions

- **Always run**: `sbt scalafmt Test/scalafmt` before submitting PRs
- **Add unit tests** for any changes
- **Follow existing patterns** in the codebase
- **Prefix PR titles** with affected module: `[javasrc2cpg] Addition Operator Fix`

## Key File Locations

- **Main build file**: `build.sbt`
- **Project definitions**: `project/Projects.scala`
- **Frontend implementations**: `joern-cli/frontends/*/src/main/scala/`
- **Query language**: `semanticcpg/src/main/scala/io/shiftleft/semanticcpg/language/`
- **Console implementation**: `console/src/main/scala/io/joern/console/`
- **Test scripts**: `tests/`

## Important Notes

- **JDK Requirement**: JDK 21 (minimum JDK 11 supported)
- **Scala Version**: 3.5.2
- **Graph Database**: Uses flatgraph (migrated from overflowdb in v4.0.0)
- **Maven**: Required for development dependencies
- **Language Support**: C/C++, Java, JavaScript, Python, Go, Swift, PHP, Ruby, C#, Kotlin

## CPG Query Examples

```scala
// Find all methods named "main"
cpg.method.name("main").l

// Find calls to dangerous functions
cpg.call.name("strcpy").l

// Data flow from parameters to return values
cpg.method.parameter.reachingDef.l
```

## Working with Workspaces

- **Import code**: `importCode("path/to/code")` - creates CPG with default overlays
- **List projects**: `workspace.projects`
- **Switch projects**: `open("projectName")`
- **Apply overlays**: Various overlay creators available for enhanced analysis