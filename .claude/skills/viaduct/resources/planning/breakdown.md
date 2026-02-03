# Task Breakdown for Viaduct Applications

## Overview

Breaking down a Viaduct application into implementable steps ensures you build in the right order and don't miss critical components. Follow this systematic approach for each new feature.

## Navigation

- Prerequisites: [Schema Design](schema-design.md)
- Related: [Entities](../core/entities.md), [Queries](../core/queries.md)
- Next Steps: [Mutations](../core/mutations.md)

## Standard Implementation Order

```
1. Database Schema (if applicable)
         |
         v
2. GraphQL Schema Definition
         |
         v
3. Generate Viaduct Code
         |
         v
4. Node Resolver
         |
         v
5. Query Resolvers
         |
         v
6. Mutation Resolvers
         |
         v
7. Field Resolvers (computed fields)
         |
         v
8. Policy Checkers (authorization)
         |
         v
9. Testing
```

## Phase 1: Database Schema

If you have a database, define the schema first:

```sql
-- migrations/YYYYMMDDHHMMSS_add_products.sql
CREATE TABLE public.products (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL,
    price DECIMAL(10,2) NOT NULL,
    category_id UUID REFERENCES public.categories(id),
    user_id UUID REFERENCES auth.users(id) NOT NULL,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now()
);

-- Enable Row Level Security
ALTER TABLE public.products ENABLE ROW LEVEL SECURITY;

-- Index for common queries
CREATE INDEX idx_products_category ON public.products(category_id);
CREATE INDEX idx_products_user ON public.products(user_id);
```

## Phase 2: GraphQL Schema

Define the complete GraphQL schema:

```graphql
# Product.graphqls

# Entity type
type Product implements Node @scope(to: ["default"]) {
  id: ID!
  name: String!
  price: Float!
  categoryId: String
  category: Category @resolver
  userId: String!
  createdAt: String!
  updatedAt: String!
}

# Input types
input CreateProductInput @scope(to: ["default"]) {
  name: String!
  price: Float!
  categoryId: ID @idOf(type: "Category")
}

input UpdateProductInput @scope(to: ["default"]) {
  id: ID! @idOf(type: "Product")
  name: String
  price: Float
  categoryId: ID @idOf(type: "Category")
}

# Queries
extend type Query @scope(to: ["default"]) {
  product(id: ID! @idOf(type: "Product")): Product @resolver
  products: [Product!]! @resolver
  productsByCategory(categoryId: ID! @idOf(type: "Category")): [Product!]! @resolver
}

# Mutations
extend type Mutation @scope(to: ["default"]) {
  createProduct(input: CreateProductInput!): Product! @resolver
  updateProduct(input: UpdateProductInput!): Product! @resolver
  deleteProduct(id: ID! @idOf(type: "Product")): Boolean! @resolver
}
```

## Phase 3: Generate Code

Run Viaduct code generation:

```bash
./gradlew generateViaductTypes
# or
./gradlew build
```

This generates:
- `NodeResolvers.Product` base class
- `QueryResolvers.Product`, `QueryResolvers.Products`, etc.
- `MutationResolvers.CreateProduct`, etc.
- `ProductResolvers.Category` for the field resolver
- Input types, GRT builders

## Phase 4: Node Resolver

Implement the Node Resolver first (foundation for everything else):

```kotlin
@Resolver
class ProductNodeResolver @Inject constructor(
    private val productService: ProductServiceClient
) : NodeResolvers.Product() {

    override suspend fun batchResolve(
        contexts: List<Context>
    ): List<FieldValue<Product>> {
        val ids = contexts.map { it.id.internalID }
        val products = productService.fetchBatch(ids)

        return contexts.map { ctx ->
            val data = products[ctx.id.internalID]
            if (data == null) {
                FieldValue.ofError(RuntimeException("Product not found"))
            } else {
                FieldValue.ofValue(
                    Product.Builder(ctx)
                        .name(data.name)
                        .price(data.price)
                        .categoryId(data.categoryId)
                        .userId(data.userId)
                        .createdAt(data.createdAt)
                        .updatedAt(data.updatedAt)
                        .build()
                )
            }
        }
    }
}
```

## Phase 5: Query Resolvers

Implement queries:

```kotlin
// Single product query
@Resolver
class ProductQueryResolver @Inject constructor(
    private val productService: ProductServiceClient
) : QueryResolvers.Product() {

    override suspend fun resolve(ctx: Context): Product? {
        val id = ctx.arguments.id.internalID
        val data = productService.fetch(id) ?: return null

        return Product.Builder(ctx)
            .id(ctx.globalIDFor(Product.Reflection, data.id))
            .name(data.name)
            .price(data.price)
            .categoryId(data.categoryId)
            .userId(data.userId)
            .createdAt(data.createdAt)
            .updatedAt(data.updatedAt)
            .build()
    }
}

// List products query
@Resolver
class ProductsQueryResolver @Inject constructor(
    private val productService: ProductServiceClient
) : QueryResolvers.Products() {

    override suspend fun resolve(ctx: Context): List<Product> {
        val products = productService.fetchAll()

        return products.map { data ->
            Product.Builder(ctx)
                .id(ctx.globalIDFor(Product.Reflection, data.id))
                .name(data.name)
                .price(data.price)
                .categoryId(data.categoryId)
                .userId(data.userId)
                .createdAt(data.createdAt)
                .updatedAt(data.updatedAt)
                .build()
        }
    }
}
```

## Phase 6: Mutation Resolvers

Implement mutations:

```kotlin
@Resolver
class CreateProductResolver @Inject constructor(
    private val productService: ProductServiceClient
) : MutationResolvers.CreateProduct() {

    override suspend fun resolve(ctx: Context): Product {
        val input = ctx.arguments.input
        val userId = ctx.requestContext.userId

        val data = productService.create(
            name = input.name,
            price = input.price,
            categoryId = input.categoryId?.internalID,
            userId = userId
        )

        return Product.Builder(ctx)
            .id(ctx.globalIDFor(Product.Reflection, data.id))
            .name(data.name)
            .price(data.price)
            .categoryId(data.categoryId)
            .userId(data.userId)
            .createdAt(data.createdAt)
            .updatedAt(data.updatedAt)
            .build()
    }
}
```

## Phase 7: Field Resolvers

Implement computed/relationship fields:

```kotlin
@Resolver("fragment _ on Product { categoryId }")
class ProductCategoryResolver : ProductResolvers.Category() {

    override suspend fun resolve(ctx: Context): Category? {
        val categoryId = ctx.objectValue.getCategoryId()
            ?: return null

        // Return node reference - Category Node Resolver handles the rest
        return ctx.nodeFor(ctx.globalIDFor(Category.Reflection, categoryId))
    }
}
```

## Phase 8: Policy Checkers (if needed)

Add authorization:

```graphql
# Add directive to schema
directive @requiresOwnership(ownerIdField: String = "userId") on OBJECT

type Product implements Node
  @scope(to: ["default"])
  @requiresOwnership {
  # ...
}
```

```kotlin
// Implement executor and factory (see policy-checkers.md)
```

## Phase 9: Testing

Write tests for each component:

```kotlin
class ProductResolversTest : FeatureAppTestBase() {

    @Test
    fun `can create and fetch product`() = runTest {
        // Create product
        val createResult = execute("""
            mutation {
                createProduct(input: {
                    name: "Test Product"
                    price: 29.99
                }) {
                    id
                    name
                    price
                }
            }
        """)

        val productId = createResult["createProduct"]["id"]

        // Fetch product
        val fetchResult = execute("""
            query(${"$"}id: ID!) {
                product(id: ${"$"}id) {
                    id
                    name
                    price
                }
            }
        """, mapOf("id" to productId))

        assertEquals("Test Product", fetchResult["product"]["name"])
        assertEquals(29.99, fetchResult["product"]["price"])
    }
}
```

## Checklist Template

Copy this checklist for each new feature:

```markdown
## Feature: [Name]

### Database
- [ ] Migration file created
- [ ] RLS policies added (if using Supabase)
- [ ] Indexes created

### GraphQL Schema
- [ ] Type implements Node
- [ ] @scope directive added
- [ ] CreateXInput defined
- [ ] UpdateXInput defined with @idOf
- [ ] Query by ID with @idOf
- [ ] List query
- [ ] CRUD mutations
- [ ] Relationship fields with @resolver

### Code Generation
- [ ] ./gradlew build passes

### Resolvers
- [ ] Node Resolver (with batchResolve)
- [ ] Query Resolvers
- [ ] Mutation Resolvers
- [ ] Field Resolvers for @resolver fields

### Authorization
- [ ] Policy directive (if needed)
- [ ] Policy executor
- [ ] Policy factory registered

### Testing
- [ ] Node resolver tests
- [ ] Query tests
- [ ] Mutation tests
- [ ] Authorization tests
```

## See Also

- [Schema Design](schema-design.md) - Schema patterns
- [Entities](../core/entities.md) - Resolver implementation
- [Policy Checkers](../gotchas/policy-checkers.md) - Authorization
