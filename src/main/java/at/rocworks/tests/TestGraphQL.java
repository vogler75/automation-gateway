package at.rocworks.tests;

import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.schema.DataFetcher;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;

import static graphql.schema.idl.TypeRuntimeWiring.newTypeWiring;


public class TestGraphQL {

    public static void main(String[] args) {
        new TestGraphQL().main();
    }

    public void main() {
        String schema = "type Query { hello(text: String): String }";

        SchemaParser schemaParser = new SchemaParser();
        TypeDefinitionRegistry typeDefinitionRegistry = schemaParser.parse(schema);

        RuntimeWiring runtimeWiring = RuntimeWiring.newRuntimeWiring()
                //.type("Query", builder -> builder.dataFetcher("hello", new StaticDataFetcher("world")))
                .type(newTypeWiring("Query")
                        .dataFetcher("hello", getBookByIdDataFetcher()))
                .build();

        SchemaGenerator schemaGenerator = new SchemaGenerator();
        GraphQLSchema graphQLSchema = schemaGenerator.makeExecutableSchema(typeDefinitionRegistry, runtimeWiring);

        GraphQL build = GraphQL.newGraphQL(graphQLSchema).build();
        ExecutionResult executionResult = build.execute("{hello(text: \"teasdfasdfst\")}");

        System.out.println(executionResult.toString());
        // Prints: {hello=world}
    }

    public DataFetcher getBookByIdDataFetcher() {
        return dataFetchingEnvironment -> {
            String test = dataFetchingEnvironment.getArgument("text");
            return test;
        };
    }
}
