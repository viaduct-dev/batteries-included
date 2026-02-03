---
name: viaduct
description: Viaduct GraphQL framework development guide for building type-safe Kotlin APIs. Use when working with .graphqls schema files, implementing NodeResolvers, FieldResolvers, QueryResolvers, or MutationResolvers, using @resolver/@scope/@idOf directives, handling GlobalID patterns with .internalID, or troubleshooting Viaduct build errors.
---

# Viaduct Application Development Guide

Viaduct is a GraphQL framework that generates type-safe Kotlin code from GraphQL schemas. Follow these patterns exactly to build working APIs.

## Quick Reference

| Task | Documentation |
|------|---------------|
| Define a new entity type | [Entities](resources/core/entities.md) |
| Add a query field | [Queries](resources/core/queries.md) |
| Add a mutation | [Mutations](resources/core/mutations.md) |
| Handle GlobalIDs | [GlobalID Guide](resources/gotchas/global-ids.md) |
| Entity relationships | [Relationships](resources/core/relationships.md) |
| Control API visibility | [Scopes](resources/core/scopes.md) |

## Architecture

```
GraphQL Schema (.graphqls)
        |
   [Viaduct Codegen]
        |
        v
Generated Base Classes (Kotlin)
        |
   [Your Implementation]
        |
        v
Working GraphQL API
```

**Core Concepts:**
- **Node Types**: Types implementing `Node` interface are resolvable by GlobalID
- **Resolvers**: Node Resolvers (fetch by ID) and Field Resolvers (compute fields)
- **GlobalIDs**: Type-safe identifiers encoding type name + internal ID

## Development Workflow

1. **Define Schema** - Create `.graphqls` files with types, queries, mutations
2. **Generate Code** - Run `./gradlew generateViaduct...` to create base classes
3. **Implement Resolvers** - Extend generated base classes
4. **Test** - Write integration tests for your resolvers

## Documentation

### Core Implementation
- [Entities](resources/core/entities.md) - Node types, node resolvers, field resolvers
- [Queries](resources/core/queries.md) - Query fields, batch resolution
- [Mutations](resources/core/mutations.md) - Mutation implementation
- [Relationships](resources/core/relationships.md) - Entity relationships, subqueries
- [Scopes](resources/core/scopes.md) - API visibility control with @scope directive
- [GlobalID Handling](resources/gotchas/global-ids.md) - Working with GlobalIDs and @idOf

### Planning & Design
- [Schema Design Patterns](resources/planning/schema-design.md) - How to design your GraphQL schema
- [Task Breakdown](resources/planning/breakdown.md) - Decomposing Viaduct applications

### Reference
- [Troubleshooting](resources/reference/troubleshooting.md) - Common errors and solutions
