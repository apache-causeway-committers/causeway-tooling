/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.causewaystuff.companion.codegen.domgen;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.causewaystuff.commons.base.types.ResourceFolder;
import org.causewaystuff.companion.codegen.model.OrmModel;
import org.causewaystuff.tooling.javapoet.ClassName;
import org.causewaystuff.tooling.javapoet.JavaFile;
import org.causewaystuff.tooling.javapoet.TypeSpec;

import org.springframework.lang.Nullable;
import org.springframework.util.ReflectionUtils;

import org.apache.causeway.commons.collections.Can;
import org.apache.causeway.commons.internal.assertions._Assert;
import org.apache.causeway.commons.internal.base._Strings;
import org.apache.causeway.commons.internal.collections._Multimaps;

import lombok.Builder;
import lombok.NonNull;
import lombok.Value;
import lombok.val;
import lombok.experimental.Accessors;

public record DomainGenerator(@NonNull DomainGenerator.Config config) {

    @Value @Builder @Accessors(fluent=true)
    public static class Config {
        private final ResourceFolder destinationFolder;
        @Builder.Default
        private final @NonNull String logicalNamespacePrefix = "";
        @Builder.Default
        private final @NonNull String packageNamePrefix = "";
        @Builder.Default
        private final @NonNull LicenseHeader licenseHeader = LicenseHeader.NONE;
        private final @NonNull OrmModel.Schema schema;
        @Builder.Default
        private final @NonNull String entitiesModulePackageName = "";
        @Builder.Default
        private final @NonNull String entitiesModuleClassSimpleName = "EntitiesModule";

        @Builder.Default
        private final @NonNull Predicate<File> onPurgeKeep = file->false;

        /**
         * Data Federation Support
         * @see <a href="https://www.datanucleus.org/products/accessplatform_6_0/jdo/persistence.html#data_federationv">Data Federation</a>
         */
        private final @Nullable String datastore;

        public String prefixed(final String prefix, final String realativeName) {
            return Can.of(
                    _Strings.emptyToNull(prefix),
                    _Strings.emptyToNull(realativeName))
                    .stream()
                    .collect(Collectors.joining("."));
        }
        public String fullLogicalName(final String realativeName) {
            return prefixed(logicalNamespacePrefix(), realativeName);
        }
        public String fullPackageName(final String realativeName) {
            return prefixed(packageNamePrefix(), realativeName);
        }
        public ClassName javaPoetClassName(final OrmModel.Entity entityModel) {
            return ClassName.get(fullPackageName(entityModel.namespace()), entityModel.name());
        }
    }

    public record QualifiedType(String packageName, TypeSpec typeSpec) {
        /** */
        public static QualifiedType nested(final TypeSpec typeSpec) {
            return new QualifiedType("", typeSpec);
        }
        public ClassName className() {
            return ClassName.get(
                    packageName,
                    (String) ReflectionUtils.getField(ReflectionUtils.findField(typeSpec.getClass(), "name"), typeSpec));
        }
    }

    public record JavaFileModel(
            @NonNull QualifiedType qualifiedType,
            @NonNull LicenseHeader licenseHeader) {
        public static JavaFileModel create(
                final DomainGenerator.Config config,
                final QualifiedType qualifiedType) {
            return new JavaFileModel(
                    qualifiedType, config.licenseHeader());
        }
        public ClassName className() {
            return qualifiedType().className();
        }
        /**
         * Does not include license header, this is only written later via {@link _DomainWriter}.
         */
        public JavaFile buildJavaFile() {
            val javaFileBuilder = JavaFile.builder(qualifiedType().packageName(), qualifiedType().typeSpec())
                    .addFileComment("Auto-generated by Causeway-Stuff code generator.")
                    .indent("    ");
            return javaFileBuilder.build();
        }
    }

    public record DomainModel(
            @NonNull OrmModel.Schema schema,
            @NonNull List<JavaFileModel> configBeans,
            @NonNull List<JavaFileModel> modules,
            @NonNull List<JavaFileModel> entities,
            @NonNull List<JavaFileModel> entityMixins,
            @NonNull List<JavaFileModel> submodules,
            @NonNull List<JavaFileModel> superTypes,
            @NonNull List<JavaFileModel> menus) {

        DomainModel(final OrmModel.Schema schema) {
            this(schema, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                    new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }
        Stream<JavaFileModel> streamJavaModels() {
            return Stream.of(configBeans, modules, menus, superTypes, entities, submodules, entityMixins)
                    .flatMap(List::stream);
        }
    }

    public DomainModel createDomainModel() {

        val entityModels = config().schema().entities().values().stream().toList();

        val domainModel = new DomainModel(config().schema());

        // superTypes
        entityModels.stream()
            .filter(entityModel->entityModel.hasSuperType())
            .sorted((a, b)->a.superType().compareTo(b.superType()))
            .map(superTypeHolder->JavaFileModel.create(config(),
                    _GenInterface.qualifiedType(config(), superTypeHolder)))
            .forEach(domainModel.superTypes()::add);

        // entities
        entityModels.stream()
            .map(entityModel->JavaFileModel.create(config(),
                    _GenEntity.qualifiedType(config(), entityModel)))
            .forEach(domainModel.entities()::add);

        // entity mixins
        var dependantMixnSpecsByEntity = _Multimaps.<OrmModel.Entity, _GenDependants.DependantMixinSpec>newListMultimap();
        entityModels.stream()
            .forEach(entityModel->{

                // delete mixin
                domainModel.entityMixins().add(
                    JavaFileModel.create(config(),
                            _GenDeleteMixin.qualifiedType(config(), entityModel)));

                entityModel.fields().stream()
                .filter(field->field.hasForeignKeys())
                .forEach(field->{
                    val foreignFields = field.foreignFields();

                    final JavaFileModel associationMixinModel;

                    domainModel.entityMixins().add(associationMixinModel =
                            JavaFileModel.create(config, _GenAssociationMixin.qualifiedType(config, field, foreignFields)));

                    val associationMixin = associationMixinModel.className();

                    // for each association mixin created, there is at least one collection counterpart
                    final List<Can<OrmModel.Field>> foreignFieldGroups =  switch (foreignFields.getCardinality()) {
                        case ZERO -> List.of(); // unexpected code reach
                        case ONE -> List.of(foreignFields);
                        case MULTIPLE -> {
                            val result = new ArrayList<Can<OrmModel.Field>>();
                            // group foreign fields by foreign entity, then for each foreign entity create a collection mixin
                            val multiMap = _Multimaps.<OrmModel.Entity, OrmModel.Field>newListMultimap();
                            foreignFields.forEach(foreignField->multiMap.putElement(foreignField.parentEntity(), foreignField));
                            multiMap.forEach((foreignEntity, groupedForeignFields)->{
                                result.add(Can.ofCollection(groupedForeignFields));
                            });
                            yield result;
                        }
                    };

                    // consolidate into multi valued map grouped by entity type
                    foreignFieldGroups.forEach(foreignFieldGgroup->{
                        var depMixin = new _GenDependants.DependantMixinSpec(field, foreignFieldGgroup, associationMixin);
                        dependantMixnSpecsByEntity.putElement(depMixin.localEntity(), depMixin);
                    });

                });
            });

        // submodules
        entityModels.stream()
            .forEach(entityModel->{
                var mixinSpecs = Can.ofCollection(
                        dependantMixnSpecsByEntity.getOrElseEmpty(entityModel));
                domainModel.submodules().add(
                    JavaFileModel.create(config(),
                            _GenDependants.qualifiedType(config(), entityModel, mixinSpecs)));
            });

        // assert, that we have no mixin created twice
        {
            val mixinNames = new HashSet<String>();
            val messages = new ArrayList<String>();
            domainModel.entityMixins().stream().map(x->x.className().canonicalName())
                .forEach(fqcn->{if(!mixinNames.add(fqcn)) { messages.add(String.format("duplicated mixin %s", fqcn));}});
            _Assert.assertTrue(messages.isEmpty(), ()->messages.stream().collect(Collectors.joining("\n")));
        }

        // config beans
        domainModel.configBeans().add(
                JavaFileModel.create(config(),
                        _GenConfigBean.qualifiedType(config(), domainModel)));

        // module
        domainModel.modules().add(
                JavaFileModel.create(config(),
                        _GenModule.qualifiedType(config(), domainModel)));

        // menu entries
        domainModel.menus().add(
                JavaFileModel.create(config(),
                        _GenMenu.qualifiedType(config(), entityModels)));

        return domainModel;
    }

    public void writeToDirectory(final @NonNull File dest) {
        _DomainWriter.writeToDirectory(createDomainModel().streamJavaModels(), dest);
    }

}
