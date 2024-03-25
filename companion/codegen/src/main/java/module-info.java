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
module org.causewaystuff.companion.codegen {
    exports org.causewaystuff.companion.codegen.domgen;
    exports org.causewaystuff.companion.codegen.model;
    exports org.causewaystuff.companion.codegen.structgen;

    requires lombok;
    requires jakarta.inject;
    requires java.compiler;
    requires transitive org.causewaystuff.tooling.javapoet;
    requires transitive org.causewaystuff.tooling.structurizr;
    requires transitive org.apache.causeway.applib;
    requires org.apache.causeway.commons;
    requires org.causewaystuff.commons.base;
    requires org.causewaystuff.companion.applib;
    requires spring.context;
    requires spring.core;
}