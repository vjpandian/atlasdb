/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.palantir.lock;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.CharMatcher;
import com.google.common.io.BaseEncoding;
import com.google.common.primitives.UnsignedBytes;
import com.palantir.logsafe.Unsafe;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.locks.ReadWriteLock;
import javax.annotation.concurrent.Immutable;

/**
 * A descriptor for a {@link ReadWriteLock}.
 *
 * @author jtamer
 */
@Unsafe
@Immutable
public class LockDescriptor implements Comparable<LockDescriptor>, Serializable {

    private static final long serialVersionUID = 1L;
    private static final CharMatcher BASIC_PRINTABLE_ASCII = CharMatcher.inRange(' ', '~');

    private final byte[] bytes;

    private transient int hashCode;

    @JsonCreator
    LockDescriptor(@JsonProperty("bytes") byte[] bytes) {
        this.bytes = bytes;
    }

    @JsonIgnore
    public String getLockIdAsString() {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public int compareTo(LockDescriptor other) {
        return UnsignedBytes.lexicographicalComparator().compare(this.bytes, other.bytes);
    }

    @Override
    public String toString() {
        String lockIdAsString = getLockIdAsString();
        return getClass().getSimpleName() + " [" + encodeToAscii(lockIdAsString) + "]";
    }

    private String encodeToAscii(String lockIdAsString) {
        return BASIC_PRINTABLE_ASCII.matchesAllOf(lockIdAsString)
                ? lockIdAsString
                : BaseEncoding.base16().encode(bytes);
    }

    public byte[] getBytes() {
        return bytes.clone();
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = 31 + Arrays.hashCode(bytes);
        }
        return hashCode;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        LockDescriptor other = (LockDescriptor) obj;
        return this.hashCode() == other.hashCode() && Arrays.equals(bytes, other.bytes);
    }
}
