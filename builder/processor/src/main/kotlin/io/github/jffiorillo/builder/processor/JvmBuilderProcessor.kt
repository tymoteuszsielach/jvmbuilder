/*
 * Copyright (C) 2018 Jose Francisco Fiorillo Verenzuela
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.jffiorillo.builder.processor

import com.google.auto.service.AutoService
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import io.github.jffiorillo.builder.JvmBuilder
import me.eugeniomarletti.kotlin.metadata.*
import me.eugeniomarletti.kotlin.metadata.shadow.metadata.ProtoBuf
import me.eugeniomarletti.kotlin.metadata.shadow.metadata.deserialization.NameResolver
import me.eugeniomarletti.kotlin.processing.KotlinAbstractProcessor
import java.io.File
import javax.annotation.processing.Messager
import javax.annotation.processing.Processor
import javax.annotation.processing.RoundEnvironment
import javax.lang.model.SourceVersion
import javax.lang.model.element.Element
import javax.lang.model.element.TypeElement
import javax.tools.Diagnostic.Kind.ERROR
import javax.tools.Diagnostic.Kind.NOTE

@AutoService(Processor::class)
class JvmBuilderProcessor : KotlinAbstractProcessor(), KotlinMetadataUtils {

  private val annotationName = JvmBuilder::class.java.canonicalName

  private lateinit var metadataHelper: MetadataHelper

  override fun getSupportedAnnotationTypes() = setOf(annotationName)

  override fun getSupportedSourceVersion(): SourceVersion = SourceVersion.latest()

  override fun process(annotations: Set<TypeElement>, roundEnv: RoundEnvironment): Boolean {
    val annotatedElements = roundEnv.getElementsAnnotatedWith(elementUtils.getTypeElement(annotationName))
    if (annotatedElements.isEmpty()) return false

    annotatedElements.filter { it.kotlinMetadata !is KotlinClassMetadata }.forEach { errorMustBeDataClass(it) }

    annotatedElements.filter { it.kotlinMetadata is KotlinClassMetadata }.map { generateBuilder(it) }
    return true
  }

  private fun generateBuilder(element: Element) {
    val metadata = element.kotlinMetadata

    if (metadata !is KotlinClassMetadata) {
      errorMustBeDataClass(element)
      return
    }

    val classData = metadata.data
    metadataHelper = MetadataHelper(classData.classProto, classData.nameResolver, element as TypeElement, messager)
    with(metadataHelper) {
      val fqClassName = getNameUsingNameResolver().replace('/', '.')
      val `package` = getNameUsingNameResolver().substringBeforeLast('/').replace('/', '.')


      val className = fqClassName.substringAfter(`package`).replace(".", "")

      val builderClassName = "JvmBuilder_$className"
      val jvmBuilderPrefix = element.getAnnotation(JvmBuilder::class.java).prefix

      val typeArguments = generateParameterizedTypes()
      val functionsReturnClass = generateFunctionsReturnClass(typeArguments, "$`package`.$builderClassName")

      val parameters = generatePropertyAndBuilderFunPerProperty(jvmBuilderPrefix, functionsReturnClass)


      val fileName = "$builderClassName.kt"
      val path = File(generatedDir, "").toPath()
      printMessageIfDebug("package = $`package`, fileName = $fileName, path = $path")

      val builderFun = generateBuildFunction(classProto, generateBuildFunctionReturnsClass(typeArguments, element), element)

      FileSpec.builder(`package`, builderClassName)
          .addComment("Code auto-generated by JvmBuilder. Do not edit.")
          .addType(TypeSpec.classBuilder(builderClassName)
              .addProperties(parameters.map { it.first })
              .addFunctions(parameters.map { it.second })
              .addFunction(builderFun)
              .addTypeVariables(typeArguments)
              .build()
          )
          .build()
          .writeTo(path)
    }
  }

  private fun generatePropertyAndBuilderFunPerProperty(prefix: String, typeName: TypeName) = with(metadataHelper) {
    classProto.constructorList
        .single { it.isPrimary }
        .valueParameterList
        .map { valueParameter ->
          val name = valueParameter.getNameUsingNameResolver()
          val type = valueParameter.resolveType()
          val functionName = if (prefix.isNotEmpty()) prefix + name.capitalize() else name
          Pair(PropertySpec.varBuilder(name,
              type.resolve(), KModifier.PRIVATE)
              .initializer("null").build(),
              FunSpec.builder(functionName)
                  .addParameter(ParameterSpec.builder(name, type).build())
                  .returns(typeName)
                  .addStatement("this.$name = $name")
                  .addStatement("return this").build()
          )
        }
  }

  private fun generateParameterizedTypes() = with(metadataHelper) {
    classProto.typeParameterList
        .map { typeArgument ->
          val parameterizedTypeClass = typeArgument.upperBoundOrBuilderList
              .map {
                when (it) {
                  is ProtoBuf.Type.Builder -> it.build().resolveType()
                  is ProtoBuf.Type -> it.resolveType()
                  else -> {
                    throw IllegalArgumentException("$it bounds")
                  }
                }
              }.toTypedArray()
          return@map if (parameterizedTypeClass.isNotEmpty())
            TypeVariableName(typeArgument.getNameUsingNameResolver(), *parameterizedTypeClass)
          else
            TypeVariableName(typeArgument.getNameUsingNameResolver())
        }
  }

  private fun generateBuildFunction(classProto: ProtoBuf.ClassOrBuilder, className: TypeName, element: TypeElement): FunSpec {
    with(metadataHelper) {
      val creationAssignment = classProto.constructorList
          .single { it.isPrimary }
          .valueParameterList
          .filter { !it.declaresDefaultValue }
          .map { valueParameter ->
            val name = valueParameter.getNameUsingNameResolver()
            val type = valueParameter.resolveType()
            return@map "$name = ${if (type.nullable) "this.$name" else "this.$name!!"}"
          }.joinToString(prefix = "var result = ${element.asClassName()}(", postfix = ")${System.getProperty("line.separator")}")


      val useProvidedValuesForDefaultValues = classProto.constructorList
          .single { it.isPrimary }
          .valueParameterList
          .filter { it.declaresDefaultValue }
          .map { valueParameter ->
            val name = valueParameter.getNameUsingNameResolver()
            return@map "$name = this.$name ?: result.$name"
          }.joinToString(prefix = "result = result.copy(", postfix = ")${System.getProperty("line.separator")}")

      return FunSpec.builder("build").let {
        it.returns(className)
        it.addCode(creationAssignment)
        if (useProvidedValuesForDefaultValues != "result.copy()") {
          it.addCode(useProvidedValuesForDefaultValues)
        }
        it.addCode("return result${System.getProperty("line.separator")}")
        it.build()
      }
    }
  }

  private fun generateFunctionsReturnClass(typeArguments: List<TypeVariableName>, fqBuilderClassName: String): TypeName =
      if (typeArguments.isNotEmpty())
        ClassName.bestGuess(fqBuilderClassName).parameterizedBy(*typeArguments.toTypedArray())
      else
        ClassName.bestGuess(fqBuilderClassName)


  private fun generateBuildFunctionReturnsClass(typeArguments: List<TypeVariableName>, element: TypeElement): TypeName =
      if (typeArguments.isNotEmpty())
        element.asClassName().parameterizedBy(*typeArguments.toTypedArray())
      else
        element.asClassName()


  private fun errorMustBeDataClass(element: Element) {
    messager.printMessage(ERROR,
        "@${JvmBuilder::class.java.simpleName} can't be applied to $element: must be a Kotlin data class", element)
  }


  internal class MetadataHelper(
      val classProto: ProtoBuf.Class,
      private val nameResolver: NameResolver,
      private val element: TypeElement,
      private val messager: Messager) {

    private val appliedType = AppliedType.get(element)
    private val jvmBuilder = element.getAnnotation(JvmBuilder::class.java)

    fun ProtoBuf.ValueParameterOrBuilder.getNameUsingNameResolver() = nameResolver.getString(this.name)
    fun ProtoBuf.TypeParameterOrBuilder.getNameUsingNameResolver() = nameResolver.getString(this.name)
    fun getNameUsingNameResolver() = nameResolver.getString(classProto.fqName)
    fun ProtoBuf.ValueParameterOrBuilder.resolveType() = type.asTypeName(nameResolver, classProto::getTypeParameter, false)
    fun ProtoBuf.Type.resolveType() = asTypeName(nameResolver, classProto::getTypeParameter, false)
    fun TypeName.resolve() = appliedType.resolver.resolve(this.asNullable())

    fun printMessageIfDebug(message: String) {
      if (jvmBuilder.debug) {
        messager.printMessage(NOTE, "JvmBuilder: $message")
      }
    }

  }

}