package io.kloudformation.specification

import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.squareup.kotlinpoet.*
import io.kloudformation.Value
import io.kloudformation.model.KloudFormationTemplate
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.expect

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SpecificationPoetTest{

    private val files = SpecificationPoet.generateSpecs(jacksonObjectMapper().configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true).readValue(SpecificationPoetTest::class.java.classLoader.getResourceAsStream("TestSpecification.json")))
    private val propertyPackage = "io.kloudformation.property"

    @Nested
    inner class OddNamed {
        @Test
        fun `should be in alexa package`() {
            val alexaSomethingElse = files.find { it.name == "Else" } ?: throw AssertionError("Else file must exist")
            expect("io.kloudformation.property.alexa.something") { alexaSomethingElse.packageName }
        }

        @Test
        fun `should have tag class under resource package`() {
            val tag = files.find { it.name == "Tag" } ?: throw AssertionError("Tag file must exist")
            expect("io.kloudformation.resource") { tag.packageName }
        }
    }

    @Nested
    inner class SomeProperty {

        private val `package` = "$propertyPackage.aws.ec2.instance"
        private val className = "SomeProperty"

        val someProperty = files.find { it.name == className }
                ?: throw AssertionError("$className file must exist")

        @Test
        fun `should be in property package under ec2 instance`() {
            expect(`package`) { someProperty.packageName }
        }

        @Nested
        inner class BuilderFunction {

            private val function = (someProperty.members.firstOrNull { it is FunSpec }
                    ?: throw AssertionError("builder function must exist")) as FunSpec

            @Test
            fun `should be called someProperty and have receiver of type KloudFormationTemplate Builder`() {
                expect(className.decapitalize()) { function.name }
                expect(KloudFormationTemplate.Builder::class.asTypeName()) { function.receiverType }
            }

            @Test
            fun `should have one required parameter plus builder function as parameters`() {
                expect(2) { function.parameters.count() }
            }

            @Test
            fun `should have propOne as first parameter and should be of type 'Value of type String'`() {
                with(function.parameters[0]) {
                    expect("propOne") { name }
                    expect(ParameterizedTypeName.get(Value::class.asTypeName(), String::class.asTypeName())) { type }
                }
            }

            @Test
            fun `should have builder as second parameter and should be of type 'SomeProperty Builder lambda'`() {
                with(function.parameters[1]) {
                    expect("builder") { name }
                    expect(LambdaTypeName::class) { type::class }
                    with(type as LambdaTypeName) {
                        expect(ClassName.bestGuess("$className.Builder")) { receiver }
                        expect(0) { parameters.count() }
                        expect(ClassName.bestGuess("$className.Builder")) { returnType }
                        expect("{ this }") { defaultValue.toString() }
                    }
                }
            }

            @Test
            fun `should result in invocation of builder function then call of build`() {
                expect("return builder( $className.create(propOne = propOne) ).build()") {
                    function.body.toString().trim()
                }
            }
        }

        @Nested
        inner class DataClass {

            private val type = (someProperty.members.firstOrNull { it is TypeSpec }
                    ?: throw AssertionError("type must exist")) as TypeSpec

            @Test
            fun `should be a data class called SomeProperty`() {
                with(type) {
                    expect(className) { name }
                    expect(setOf(KModifier.DATA)) { modifiers }
                }
            }

            @Test
            fun `should have two properties`() {
                with(type) {
                    expect(2) { propertySpecs.count() }
                    expect(2) { primaryConstructor!!.parameters.count() }
                }
            }

            @Test
            fun `should have one required property named propOne of type 'Value of type String'`() {
                with(type.propertySpecs[0]) {
                    expect("propOne") { name }
                    expect(ParameterizedTypeName.get(Value::class.asTypeName(), String::class.asTypeName())) { type }
                    expect("propOne"){ initializer.toString() }
                }
                with(type.primaryConstructor!!.parameters[0]) {
                    expect("propOne") { name }
                    expect(ParameterizedTypeName.get(Value::class.asTypeName(), String::class.asTypeName())) { type }
                }
            }

            @Test
            fun `should have one non required property named propTwo of type 'List of type SomeSubProperty'`() {
                with(type.propertySpecs[1]) {
                    expect("propTwo") { name }
                    expect((List::class ofType ClassName.bestGuess(SomeSubProperty().canonicalName)).asNullable()) { type }
                    expect("propTwo"){ initializer.toString() }
                }
                with(type.primaryConstructor!!.parameters[1]) {
                    expect("propTwo") { name }
                    expect((List::class ofType ClassName.bestGuess(SomeSubProperty().canonicalName)).asNullable())  { type }
                    expect("null"){ defaultValue.toString() }
                }
            }
            @Nested
            inner class Companion {
                private val companion = type.companionObject ?: throw AssertionError("Companion Object must exist")

                @Test
                fun `should have one function named create that returns a Builder passing all required properties`() {
                    with(companion){
                        expect(1, "Should only have one method named create") { funSpecs.count() }
                        with(funSpecs[0]){
                            expect( "create") { name }
                            expect(1){ parameters.count() }
                            with(parameters[0]){
                                expect("propOne") { name }
                                expect(Value::class ofType String::class.asTypeName()) { type }
                            }
                            expect( "return Builder(propOne = propOne)" ){ body.toString().trim() }
                        }
                    }
                }
            }


            @Nested
            inner class Builder {
                private val builder = if(type.typeSpecs.count() != 1) throw AssertionError("Data class should have only one static class named Builder") else type.typeSpecs[0]

                @Test
                fun `should be a class called Builder`() {
                    with(builder) {
                        expect("Builder") { name }
                        expect(0){ modifiers.count() }
                    }
                }

                @Test
                fun `should have two properties but only one in the constructor for the required property`() {
                    with(builder) {
                        expect(2) { propertySpecs.count() }
                        expect(1) { primaryConstructor!!.parameters.count() }
                    }
                }

                @Test
                fun `should have one required property named propOne of type 'Value of type String'`() {
                    with(builder.propertySpecs[1]) {
                        expect("propOne") { name }
                        expect(ParameterizedTypeName.get(Value::class.asTypeName(), String::class.asTypeName())) { type }
                        expect("propOne"){ initializer.toString() }
                    }
                    with(builder.primaryConstructor!!.parameters[0]) {
                        expect("propOne") { name }
                        expect(ParameterizedTypeName.get(Value::class.asTypeName(), String::class.asTypeName())) { type }
                    }
                }

                @Test
                fun `should have one non required property named propTwo of type 'List of type SomeSubProperty'`() {
                    with(builder.propertySpecs[0]) {
                        expect("propTwo") { name }
                        expect((List::class ofType ClassName.bestGuess(SomeSubProperty().canonicalName)).asNullable()) { type }
                        expect("null"){ initializer.toString() }
                    }
                }

                @Test
                fun `should have one two functions'`() {
                    expect(2){ builder.funSpecs.count() }
                }

                @Test
                fun `should have one function named propTwo that sets the local propTwo variable and returns the builder`() {
                    with(builder.funSpecs[0]) {
                        expect("propTwo") { name }
                        expect(1){ parameters.count() }
                        with(parameters[0]) {
                            expect(List::class ofType ClassName.bestGuess(SomeSubProperty().canonicalName)) { type }
                        }
                        expect("return also { it.propTwo = propTwo }"){ body.toString().trim() }
                    }
                }

                @Test
                fun `should have one function named build that returns an instance of SomeProperty with all properties set`() {
                    with(builder.funSpecs[1]) {
                        expect("build") { name }
                        expect(0){ parameters.count() }
                        expect("return SomeProperty( propOne = propOne, propTwo = propTwo)"){ body.toString().trim() }
                    }
                }
            }
        }

        @Nested
        inner class SomeSubProperty {
            private val `package` = "io.kloudformation.property.aws.ec2.instance.someproperty"
            private val name = "SomeSubProperty"
            val canonicalName = "$`package`.$name"
        }
    }
}