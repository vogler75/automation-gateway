# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is an Automation Gateway project built with Kotlin and Vert.x that provides a unified interface for industrial automation protocols and data logging. The gateway supports multiple drivers (OPC UA, MQTT, PLC4X) and can log data to various databases and time-series stores.

## Architecture

### Core Components

The project follows a modular architecture with these main components:

1. **Drivers** - Protocol implementations for data acquisition:
   - OPC UA Driver (`lib-core/src/main/kotlin/at/rocworks/gateway/core/opcua/OpcUaDriver.kt`)
   - MQTT Driver (`lib-core/src/main/kotlin/at/rocworks/gateway/core/mqtt/MqttDriver.kt`)
   - PLC4X Driver (`lib-plc4x/src/main/kotlin/Plc4xDriver.kt`)

2. **Servers** - Protocol servers for data exposure:
   - OPC UA Server (`lib-core/src/main/kotlin/at/rocworks/gateway/core/opcua/OpcUaServer.kt`)
   - MQTT Server (`lib-core/src/main/kotlin/at/rocworks/gateway/core/mqtt/MqttServer.kt`)
   - GraphQL Server (`lib-core/src/main/kotlin/at/rocworks/gateway/core/graphql/GraphQLServer.kt`)

3. **Loggers** - Database and storage backends:
   - InfluxDB (`lib-influxdb`)
   - IoTDB (`lib-iotdb`)
   - Neo4j (`lib-neo4j`)
   - OpenSearch (`lib-opensearch`)
   - Cassandra (`lib-cassandra`)
   - Kafka, JDBC, MQTT loggers in `lib-core`

4. **Event System**:
   - Central EventBus (`lib-core/src/main/kotlin/at/rocworks/gateway/core/data/EventBus.kt`)
   - Topic-based data routing with `Topic`, `TopicValue`, and `DataPoint` classes

### Component Lifecycle

All components extend `Component` abstract class and are managed by `ComponentHandler`. Components are instantiated via factory pattern in the main `App.kt` files. The system uses Vert.x verticles for async, event-driven processing.

## Build Commands

```bash
# Build the project
./gradlew build

# Run the main application
./gradlew :app:run

# Run the PLC4X application
./gradlew :app-plc4x:run

# Run tests
./gradlew test

# Clean build artifacts
./gradlew clean

# Assemble without tests
./gradlew assemble
```

## Development Tasks

### Running a Single Test
```bash
./gradlew test --tests "TestClassName"
./gradlew test --tests "TestClassName.testMethodName"
```

### Check for Compilation Errors
```bash
./gradlew compileKotlin
./gradlew compileTestKotlin
```

## Project Structure

- `app/` - Main application entry point
- `app-plc4x/` - PLC4X specific application variant
- `lib-core/` - Core functionality, drivers, servers, and base loggers
- `lib-influxdb/`, `lib-iotdb/`, `lib-neo4j/`, `lib-opensearch/`, `lib-cassandra/` - Database-specific logger implementations
- `lib-plc4x/` - PLC4X driver implementation
- `test/` - Test application
- `buildSrc/` - Gradle build conventions

## Key Dependencies

- Vert.x 4.5.11 - Reactive application framework
- Kotlin JDK 8 - Primary language
- JUnit Jupiter - Testing framework
- Database-specific clients for each logger implementation

## Configuration

Components are configured via `JsonObject` passed to their constructors. The system uses Vert.x config with YAML support. Each component type has specific configuration requirements defined in its implementation.