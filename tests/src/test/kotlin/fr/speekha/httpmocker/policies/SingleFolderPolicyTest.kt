/*
 * Copyright 2019 David Blanc
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

package fr.speekha.httpmocker.policies

import fr.speekha.httpmocker.buildRequest
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class SingleFolderPolicyTest {


    @Test
    fun `should store configuration files in a single folder`() {
        val policy: FilingPolicy = SingleFolderPolicy("folder")
        val request = buildRequest(
            "http://www.somestuff.com/test/with/path", listOf("header" to "value"), "POST", "body"
        )
        Assertions.assertEquals("folder/test_with_path.json", policy.getPath(request))
    }

    @Test
    fun `should handle empty root folder`() {
        val policy: FilingPolicy = SingleFolderPolicy("")
        val request = buildRequest(
            "http://www.somestuff.com/test/with/path", listOf("header" to "value"), "POST", "body"
        )
        Assertions.assertEquals("test_with_path.json", policy.getPath(request))
    }

    @Test
    fun `should handle empty path segments`() {
        val policy: FilingPolicy = SingleFolderPolicy("folder")
        val request = buildRequest(
            "http://www.somestuff.com/test/with/path/", listOf("header" to "value"), "POST", "body"
        )
        Assertions.assertEquals("folder/test_with_path.json", policy.getPath(request))
    }


}