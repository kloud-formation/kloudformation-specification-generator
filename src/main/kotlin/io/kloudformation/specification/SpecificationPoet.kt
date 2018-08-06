package io.kloudformation.specification

import com.fasterxml.jackson.annotation.JsonIgnore
import com.squareup.kotlinpoet.*
import io.kloudformation.KloudResource
import io.kloudformation.Value
import io.kloudformation.model.KloudFormationTemplate
import io.kloudformation.model.iam.resource
import java.io.File

object SpecificationPoet {

    val logicalName = "logicalName"

    private data class TypeInfo(val awsTypeName: String, val canonicalName: String, val  properties: List<PropertyTypeInfo>)
    private data class PropertyTypeInfo(val name: String, val typeName: TypeName)

    fun generate(specification: Specification){
        val types = (specification.propertyTypes + specification.resourceTypes)
        val files = (specification.propertyTypes
                .map { it.key to buildFile(types.keys, false, it.key, it.value) } +
        specification.resourceTypes
                .map { it.key to buildFile(types.keys, true, it.key, it.value) }).toMap()

        val fieldMappings = files.map {
            TypeInfo(
                    awsTypeName = it.key,
                    canonicalName = it.value.packageName + "." + (it.value.members.first { it is TypeSpec } as TypeSpec).name,
                    properties = (it.value.members.first { it is TypeSpec } as TypeSpec).propertySpecs.map {
                        PropertyTypeInfo(it.name, it.type)
                    }
            )
        }
        files.map { file ->
            val type = file.value.members.first{ it is TypeSpec } as TypeSpec
            val propertyType = file.key
            val propertyInfo = types[propertyType]
            val isResource = specification.resourceTypes.containsKey(propertyType)
            FileSpec.builder(file.value.packageName, file.value.name)
                    .also { newFile ->
                        file.value.members.filter { it is FunSpec }.map { it as FunSpec }.forEach{ newFile.addFunction(it) }
                    }
                    .addType(
                            type.toBuilder()
                            .primaryConstructor(type.primaryConstructor)
                            .companionObject(companionObject(types.keys, isResource, propertyType, propertyInfo!!))
                            .addType(builderClass((specification.propertyTypes + specification.resourceTypes).keys, isResource, propertyType, propertyInfo, fieldMappings))
                            .build()
                    )
                    .build()
        }.forEach { it.writeTo(File(System.getProperty("user.dir") + "/target/generated-sources")) }
    }

    private fun buildFile(types: Set<String>, isResource: Boolean, typeName: String, propertyInfo: PropertyInfo) =
            FileSpec.builder(getPackageName(isResource, typeName), getClassName(typeName))
                    .addType(buildType(types, isResource, typeName, propertyInfo))
                    .addFunction(builderFunction(types, isResource, typeName, propertyInfo))
                    .build()

    private fun builderClassNameFrom(type: String) = ClassName.bestGuess("$type.Builder")

    private fun builderFunction(types: Set<String>, isResource: Boolean, typeName: String, propertyInfo: PropertyInfo) = FunSpec.let {
            val name = getClassName(typeName)
            it.builder(name.decapitalize()).also { func ->
                if (isResource) {
                    func.addCode( "return add( builder( $name.create(${paramListFrom(propertyInfo, true, true, name)}) ).build() )\n" )
                } else {
                    func.addCode( "return builder( $name.create(${paramListFrom(propertyInfo, false)}) ).build()\n" )
                }
                propertyInfo.properties.sorted().filter { it.value.required }.map { func.addParameter(buildParameter(types, typeName, it.key, it.value)) }
                if (isResource) {
                    func.addParameter(ParameterSpec.builder(logicalName, String::class.asClassName().asNullable()).defaultValue("null").build())
                    func.addParameter(ParameterSpec.builder("dependsOn", String::class.asClassName().asNullable()).defaultValue("null").build())
                }
                func.addParameter(ParameterSpec.builder("builder", LambdaTypeName.get(builderClassNameFrom(name), returnType = builderClassNameFrom(name))).defaultValue("{ this }").build())
            }
                .receiver(KloudFormationTemplate.Builder::class)
                .build()
        }


    private fun buildType(types: Set<String>, isResource: Boolean, typeName: String, propertyInfo: PropertyInfo) =
            TypeSpec.classBuilder(getClassName(typeName))
                    .addModifiers(if (!propertyInfo.properties.isEmpty() || isResource) KModifier.DATA else KModifier.PUBLIC)
                    .primaryConstructor(if (!propertyInfo.properties.isEmpty() || isResource) buildConstructor(types, isResource, typeName, propertyInfo) else null)
                    .also {
                        if(isResource)
                            it
                            .superclass(ParameterizedTypeName.get(KloudResource::class.asClassName(),String::class.asTypeName()))
                            .addSuperclassConstructorParameter("$logicalName = $logicalName")
                            .addSuperclassConstructorParameter("kloudResourceType = %S", typeName)
                            .addSuperclassConstructorParameter("dependsOn = dependsOn")
                            .addProperty(PropertySpec.builder(logicalName, String::class, KModifier.OVERRIDE).initializer(logicalName).addAnnotation(JsonIgnore::class).build())
                            .addProperty(PropertySpec.builder("dependsOn", String::class.asClassName().asNullable(), KModifier.OVERRIDE).initializer("dependsOn").addAnnotation(JsonIgnore::class).build())
                    }
                    .addFunctions(functionsFrom(propertyInfo.attributes.orEmpty()))
                    .addProperties(propertyInfo.properties.sorted().map { buildProperty(types, typeName, it.key, it.value) })
                    .build()

    private fun functionsFrom(attributes: Map<String, Attribute>) = attributes.map {
        FunSpec.builder(escape(it.key)).addCode("return %T<%T>(logicalName, %S)\n", Value.Att::class, String::class, it.key).build() //TODO replace string type here with specific attribute type
    }

    private fun Map<String, Property>.sorted() = toList().sortedWith(compareBy({ !it.second.required }, { it.first })).toMap()

    private fun builderConstructor(types: Set<String>, isResource: Boolean, typeName: String, propertyInfo: PropertyInfo) = FunSpec
            .constructorBuilder()
            .also { func ->
                if(isResource){
                    func.addParameter(ParameterSpec.builder(logicalName, String::class).build())
                }
                func.addParameters(propertyInfo.properties.sorted().filter { it.value.required }.map{ buildParameter(types, typeName, it.key, it.value) })
                if(isResource){
                    func.addParameter(ParameterSpec.builder("dependsOn", String::class.asClassName().asNullable()).defaultValue("null").build())
                }
            }
            .build()

    private fun companionObject(types: Set<String>, isResource: Boolean, typeName: String, propertyInfo: PropertyInfo) = TypeSpec.companionObjectBuilder()
            .addFunction(buildCreateFunction(types, isResource, typeName, propertyInfo))
            .build()

    private fun builderClass(types: Set<String>, isResource: Boolean, typeName: String, propertyInfo: PropertyInfo, typeMappings: List<TypeInfo>) = TypeSpec.classBuilder("Builder")
            .primaryConstructor(builderConstructor(types, isResource, typeName, propertyInfo))
            .also {
                if(isResource)
                    it.addProperty(PropertySpec.builder(logicalName, String::class).initializer(logicalName).build())
            }
            .addProperties(propertyInfo.properties.sorted().let {
                it.filter { !it.value.required }.map { buildVarProperty(types, typeName, it.key, it.value) } +
                        it.filter { it.value.required }.map { buildProperty(types, typeName, it.key, it.value) }
            })
            .also {
                if(isResource)
                    it.addProperty(PropertySpec.builder("dependsOn", String::class.asClassName().asNullable()).initializer("dependsOn").build())
            }
            .addFunctions(
                    propertyInfo.properties.filter { !it.value.required }.flatMap {
                        listOfNotNull(
                                if(it.value.itemType == null && (it.value.primitiveType != null || it.value.primitiveItemType != null))
                                    primitiveSetterFunction(it.key.decapitalize(), it.value, getType(types, typeName, it.value, wrapped = false))
                                else null,
                                if(it.value.primitiveType == null && it.value.primitiveItemType == null && it.value.itemType == null && it.value.type != null)
                                    typeSetterFunction(it.key, it.key, typeName, typeMappings)
                                else null,
                                FunSpec.builder(it.key.decapitalize())
                                        .addParameter(it.key.decapitalize(), getType(types, typeName, it.value))
                                        .addCode("return also { it.${it.key.decapitalize()} = ${it.key.decapitalize()} }\n")
                                        .build()
                        )
                    } + listOf(
                            FunSpec.builder("build")
                                    .also {
                                        val primitiveProperties = propertyInfo.properties.keys + (if(isResource) setOf("dependsOn") else emptySet())
                                        it.addCode("return ${getClassName(typeName)}( " + primitiveProperties.foldIndexed(if(isResource) logicalName + (if(primitiveProperties.isNotEmpty()) ", " else "") else "") {
                                            index, acc, item ->  acc + (if(index != 0)", " else "") + "${item.decapitalize()} = ${item.decapitalize()}"
                                        } + ")\n")
                                    }
                                    .build()
                    )
            )
            .build()

    private fun paramListFrom(propertyInfo: PropertyInfo, isResource: Boolean, addCurrentDependee: Boolean = false, specialLogicalName: String? = null): String{
        val nameList = (if(isResource) listOf(logicalName) else emptyList()) +
                propertyInfo.properties.sorted().filter { it.value.required }.keys.map { it.decapitalize() } +
                (if(isResource) listOf("dependsOn") else emptyList())
        return nameList.foldIndexed(""){
            index, acc, name -> acc + (if(index != 0) ", " else "") + "$name = " +
                (
                    if(name == "dependsOn" && addCurrentDependee) "$name ?: currentDependee"
                    else if(name == "logicalName" && !specialLogicalName.isNullOrEmpty()) "$name ?: allocateLogicalName(\"$specialLogicalName\")"
                    else name
                )
        }
    }

    private fun buildCreateFunction(types: Set<String>, isResource: Boolean, typeName: String, propertyInfo: PropertyInfo): FunSpec{
        val funSpec = if(isResource) {
            FunSpec.builder("create").addParameter(logicalName, String::class).addCode("return Builder(${paramListFrom(propertyInfo, true)})\n")
        }
        else FunSpec.builder("create").addCode("return Builder(${paramListFrom(propertyInfo, false)})\n")
        propertyInfo.properties.sorted().filter { it.value.required }.forEach { funSpec.addParameter(buildParameter(types, typeName, it.key, it.value)) }
        if(isResource) funSpec.addParameter(ParameterSpec.builder("dependsOn", String::class.asClassName().asNullable()).defaultValue("null").build())
        return funSpec.build()
    }

    private fun primitiveSetterFunction(name: String, property: Property, type: TypeName) = FunSpec.builder(name + if(property.type == "Map") "Map" else "")
            .addParameter(name, type)
            .also {
                if(property.primitiveItemType != null){
                    if(property.type == "Map") it.addCode("return also { it.$name = $name.orEmpty().map { it.key to %T(it.value) }.toMap() }\n", Value.Of::class)
                    else it.addCode("return also { it.$name = $name.orEmpty().map { %T(it) }.toTypedArray() }\n", Value.Of::class)
                }
                else if(property.primitiveType != null){
                    it.addCode("return also { it.$name = %T($name) }\n", Value.Of::class)
                }
            }
            .build()

    private fun childParamsWithTypes(parameters: Collection<String>) = parameters.fold(""){ acc, parameter -> acc + parameter + ": %T, " }
    private fun childParams(parameters: Collection<String>) = parameters.foldIndexed(""){ index, acc, parameter -> acc + (if(index != 0) ", " else "") + parameter }

    private fun typeSetterFunction(name: String, propertyType: String, typeName: String, typeMappings:  List<TypeInfo>): FunSpec{
        val parent = (typeMappings.find { it.awsTypeName == typeName }!!.properties.find { it.name == propertyType.decapitalize() }!!.typeName as ClassName)
        val requiredProperties = typeMappings.find { it.canonicalName == parent.canonicalName }!!.properties.filter { !it.typeName.nullable }
        val propertyNames = requiredProperties.map { it.name }
        val propertyTypes = requiredProperties.map { it.typeName }
        return FunSpec.builder("_" + name.decapitalize()) //TODO get LambdaTypeName working here, had to hack a stinking function to stringify my own function
                .addCode("return \"\"\nfun ${name.decapitalize()}(${childParamsWithTypes(propertyNames)} builder: ${parent.simpleName()}.Builder.() -> ${parent.simpleName()}.Builder = { this }) = ${name.decapitalize()}(${parent.simpleName()}.create(${childParams(propertyNames)}).builder().build())", *propertyTypes.toTypedArray())
                .build()
    }

    private fun buildConstructor(types: Set<String>, isResource: Boolean, classTypeName: String, propertyInfo: PropertyInfo) =
            FunSpec.constructorBuilder()
                    .addParameters(if(isResource) listOf(
                            ParameterSpec.builder(logicalName, String::class, KModifier.OVERRIDE).addAnnotation(JsonIgnore::class).build()
                    ) else emptyList())
                    .addParameters(propertyInfo.properties.toList().sortedWith(compareBy({ !it.second.required }, { it.first })).toMap().map { buildParameter(types, classTypeName, it.key, it.value) })
                    .addParameters(if(isResource) listOf(
                            ParameterSpec.builder("dependsOn", String::class.asClassName().asNullable(), KModifier.OVERRIDE).defaultValue("null").addAnnotation(JsonIgnore::class).build()
                    ) else emptyList())
                    .build()

    private fun buildProperty(types: Set<String>, classTypeName: String, propertyName: String, property: Property) =
            PropertySpec.builder(
                    propertyName.decapitalize(),
                    if (property.required) getType(types, classTypeName, property).asNonNullable() else getType(types, classTypeName, property).asNullable())
                    .initializer(propertyName.decapitalize())
                    .build()

    private fun buildVarProperty(types: Set<String>, classTypeName: String, propertyName: String, property: Property) =
            PropertySpec.varBuilder(
                    propertyName.decapitalize(),
                    getType(types, classTypeName, property).asNullable()
            ).initializer("null").build()

    private fun buildParameter(types: Set<String>, classTypeName: String, parameterName: String, property: Property) =
            if (property.required) ParameterSpec
                    .builder(parameterName.decapitalize(), getType(types, classTypeName, property).asNonNullable())
                    .build()
            else ParameterSpec
                    .builder(parameterName.decapitalize(), getType(types, classTypeName, property).asNullable())
                    .defaultValue("null")
                    .build()

    private fun getClassName(typeName: String) =
            typeName.split("::", ".").last()

    private fun getPackageName(isResource: Boolean, typeName: String) =
            "io.kloudformation.${if (isResource) "resource" else "property"}${typeName.split("::", ".").dropLast(1).joinToString(".").toLowerCase().replaceFirst("aws.", ".")}"


    private fun primitiveTypeName(primitiveType: String) = ClassName.bestGuess(primitiveType.replace("Json", "com.fasterxml.jackson.databind.JsonNode").replace("Timestamp", "java.time.Instant").replace("Integer", "kotlin.Int"))

    private fun valueTypeName(primitiveType: String, wrapped: Boolean) = if(wrapped) ParameterizedTypeName.get(Value::class.asClassName(), primitiveTypeName(primitiveType))
    else primitiveTypeName(primitiveType)

    private fun getType(types: Set<String>, classTypeName: String, property: Property, wrapped: Boolean = true) = when {
        !property.primitiveType.isNullOrEmpty() -> {
            if(wrapped)ParameterizedTypeName.get(Value::class.asClassName(), primitiveTypeName(property.primitiveType!!))
            else primitiveTypeName(property.primitiveType!!)
        }
        !property.primitiveItemType.isNullOrEmpty() -> {
            if (property.type.equals("Map"))
                ParameterizedTypeName.get(Map::class.asClassName(), String::class.asClassName(), valueTypeName(property.primitiveItemType!!, wrapped))
            else ParameterizedTypeName.get(ClassName.bestGuess("Array"), valueTypeName(property.primitiveItemType!!, wrapped))
        }
        !property.itemType.isNullOrEmpty() -> ParameterizedTypeName.get(ClassName.bestGuess("Array"), ClassName.bestGuess(getPackageName(false, getTypeName(types, classTypeName, property.itemType.toString())) + "." + property.itemType))
        else -> ClassName.bestGuess(getPackageName(false, getTypeName(types, classTypeName, property.type.toString())) + "." + property.type)
    }

    private fun getTypeName(types: Set<String>, classTypeName: String, propertyType: String) =
            types.filter { it == propertyType || it.endsWith(".$propertyType") }.let {
                if (it.size > 1) it.first { it.contains(classTypeName.split("::").last().split(".").first()) } else it.first()
            }

    private fun escape(name: String) = name.replace(".", "")
}