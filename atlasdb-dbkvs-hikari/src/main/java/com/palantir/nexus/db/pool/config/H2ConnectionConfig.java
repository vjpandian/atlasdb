/**
 * Copyright 2015 Palantir Technologies
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.palantir.nexus.db.pool.config;

import org.immutables.value.Value;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.auto.service.AutoService;
import com.palantir.nexus.db.DBType;

@AutoService(ConnectionConfig.class)
@JsonDeserialize(as = ImmutableH2ConnectionConfig.class)
@JsonSerialize(as = ImmutableH2ConnectionConfig.class)
@JsonTypeName(H2ConnectionConfig.TYPE)
@Value.Immutable
public abstract class H2ConnectionConfig extends ConnectionConfig {

    public static final String TYPE = "h2";

    @Override
    @Value.Default
    public String getUrl() {
        return "jdbc:h2:mem:";
    }

    @Override
    @Value.Default
    public String getDriverClass() {
        return "org.h2.Driver";
    }

    @Override
    @Value.Default
    public String getTestQuery() {
        return "SELECT 1";
    }

    @Override
    @Value.Derived
    public DBType getDbType() {
        return DBType.H2_MEMORY;
    }

    @Override
    public final String type() {
        return TYPE;
    }

}
