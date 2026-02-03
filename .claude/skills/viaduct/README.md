# Viaduct Claude Code Skill

A Claude Code skill providing contextual documentation for building Viaduct GraphQL applications. **Auto-triggers** when working on Viaduct projects.

## Installation

### Global Installation (all projects, auto-triggers)

```bash
ln -s ~/viaduct-skills ~/.claude/skills/viaduct
```

### Project-Specific Installation

```bash
ln -s ~/viaduct-skills .claude/skills/viaduct
```

## Auto-Triggering

The skill automatically activates when Claude detects you're working with:
- `.graphqls` schema files
- `NodeResolvers`, `FieldResolvers`, `QueryResolvers`, `MutationResolvers`
- `@resolver`, `@scope`, `@idOf` directives
- `GlobalID`, `GlobalID<T>`, `.internalID` patterns
- `CheckerExecutor`, `CheckerExecutorFactory`, policy directives
- `viaduct.api`, `viaduct.tenant`, `viaduct.engine` packages

## Manual Invocation

You can also invoke explicitly:

```
/viaduct
```

This provides contextual documentation for:
- Entity and resolver implementation
- Query and mutation patterns
- GlobalID handling (common gotchas)
- Policy checker implementation
- Schema design patterns
- Troubleshooting

## Documentation Structure

```
viaduct-skills/
├── SKILL.md                # Main skill entry point (with auto-trigger frontmatter)
├── README.md               # This file
└── resources/
    ├── planning/
    │   ├── schema-design.md    # Schema-first design patterns
    │   └── breakdown.md        # Task breakdown methodology
    ├── core/
    │   ├── entities.md         # Node and field resolvers
    │   ├── queries.md          # Query implementation
    │   ├── mutations.md        # Mutation implementation
    │   └── relationships.md    # Entity relationships
    ├── gotchas/
    │   ├── global-ids.md       # GlobalID handling
    │   └── policy-checkers.md  # Authorization
    └── reference/
        └── troubleshooting.md  # Common errors
```

## Key Topics

### Getting Started
- Start with `viaduct.md` for an overview
- Use `planning/schema-design.md` when designing your GraphQL schema
- Follow `planning/breakdown.md` for implementation order

### Core Implementation
- `core/entities.md` - Node resolvers, field resolvers, responsibility sets
- `core/queries.md` - Query fields, batch resolution, N+1 prevention
- `core/mutations.md` - CRUD mutations, input types
- `core/relationships.md` - One-to-many, many-to-many patterns

### Common Gotchas
- `gotchas/global-ids.md` - **Critical** - Avoid GlobalID mistakes
- `gotchas/policy-checkers.md` - Authorization directive implementation

### Troubleshooting
- `reference/troubleshooting.md` - Error messages and fixes

## Quick Reference

| Task | Key Pattern |
|------|-------------|
| New entity | `type X implements Node`, Node Resolver |
| Input IDs | Always use `@idOf(type: "X")` |
| In resolvers | Use `.internalID` for database |
| In policies | Handle both GlobalID and String |
| Relationships | Use `ctx.nodeFor()` for references |
| N+1 queries | Use `batchResolve` |

## Contributing

1. Keep documents under 20KB
2. Follow the document structure template
3. Include code examples
4. Document common mistakes

## License

Internal use only.
