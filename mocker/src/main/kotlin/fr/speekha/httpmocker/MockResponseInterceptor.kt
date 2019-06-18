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

package fr.speekha.httpmocker

import fr.speekha.httpmocker.model.Matcher
import fr.speekha.httpmocker.model.RequestDescriptor
import fr.speekha.httpmocker.model.ResponseDescriptor
import fr.speekha.httpmocker.policies.FilingPolicy
import okhttp3.*
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.util.*

/**
 * A OkHTTP interceptor that can let requests through or block them and answer them with predefined responses.
 * Genuine network connections can also be recorded to create reusable offline scenarios.
 */
class MockResponseInterceptor
private constructor(
    private val filingPolicy: FilingPolicy,
    private val openFile: LoadFile,
    private val mapper: Mapper
) : Interceptor {

    private var rootFolder: File? = null

    /**
     * An arbitrary delay to include when answering requests in order to have a realistic behavior (GUI can display
     * loaders, etc.)
     */
    var delay: Long = 0

    /**
     * Enables to set the interception mode. @see fr.speekha.httpmocker.MockResponseInterceptor.Mode
     */
    var mode: Mode = Mode.DISABLED
        set(value) {
            if (value == Mode.RECORD && rootFolder == null) {
                error(NO_FOLDER_ERROR)
            } else {
                field = value
            }
        }

    private val extensionMappings: Map<String, String> by lazy { loadExtensionMap() }

    private fun loadExtensionMap(): Map<String, String> =
        javaClass.classLoader.getResourceAsStream("fr/speekha/httpmocker/resources/mimetypes")
            .readAsStringList()
            .associate {
                val (extension, mimeType) = it.split("=")
                mimeType to extension
            }

    override fun intercept(chain: Interceptor.Chain): Response = when (mode) {
        Mode.DISABLED -> proceedWithRequest(chain)
        Mode.ENABLED -> mockResponse(chain.request()) ?: buildResponse(chain.request(), responseNotFound())
        Mode.MIXED -> mockResponse(chain.request()) ?: proceedWithRequest(chain)
        Mode.RECORD -> recordCall(chain)
    }

    private fun proceedWithRequest(chain: Interceptor.Chain) = chain.proceed(chain.request())

    private fun mockResponse(request: Request): Response? = loadResponse(request)?.let { response ->
        when {
            response.delay > 0 -> Thread.sleep(response.delay)
            delay > 0 -> Thread.sleep(delay)
        }
        buildResponse(request, response)
    }

    private fun loadResponse(request: Request): ResponseDescriptor? = try {
        openFile(filingPolicy.getPath(request))?.let { stream ->
            val list = mapper.readMatches(stream)
            matchRequest(request, list)
        }
    } catch (e: Throwable) {
        null
    }

    private fun buildResponse(request: Request, response: ResponseDescriptor): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(response.code)
        .message(messageForHttpCode(response.code))
        .body(loadResponseBody(request, response))
        .apply {
            response.headers.forEach {
                header(it.name, it.value)
            }
        }
        .build()

    private fun responseNotFound() = ResponseDescriptor(code = 404, body = "Page not found")

    private fun loadResponseBody(request: Request, response: ResponseDescriptor) = ResponseBody.create(
        MediaType.parse(response.mediaType), response.bodyFile?.let {
            loadResponseBodyFromFile(request, it)
        } ?: response.body.toByteArray(Charset.forName("UTF-8")))

    private fun loadResponseBodyFromFile(request: Request, it: String): ByteArray? {
        val responsePath = filingPolicy.getPath(request)
        val bodyPath = responsePath.substring(0, responsePath.lastIndexOf('/') + 1) + it
        return openFile(bodyPath)?.readBytes()
    }

    private fun matchRequest(request: Request, list: List<Matcher>): ResponseDescriptor? =
        list.firstOrNull { it.request.match(request) }?.response

    private fun RequestDescriptor.match(request: Request): Boolean =
        (method?.let { it.toUpperCase(Locale.ROOT) == request.method() } ?: true) &&
                headers.all { request.headers(it.name).contains(it.value) } &&
                params.all { request.url().queryParameter(it.key) == it.value } &&
                request.matchBody(this)

    private fun recordCall(chain: Interceptor.Chain): Response {
        val response = proceedWithRequest(chain)
        val body = response.body()?.bytes()
        saveFiles(chain.request(), response, body)
        return response.copyResponse(body)
    }

    private fun saveFiles(request: Request, response: Response, body: ByteArray?) = try {
        val storeFile = filingPolicy.getPath(request)
        val matchers = createMatcher(storeFile, request, response)
        val requestFile = File(rootFolder, storeFile)

        saveRequestFile(requestFile, matchers)

        matchers.last().response.bodyFile?.let { responseFile ->
            saveResponseBody(File(requestFile.parentFile, responseFile), body)
        }
    } catch (e: Throwable) {
        e.printStackTrace()
    }

    private fun saveResponseBody(storeFile: File, body: ByteArray?) = openFile(storeFile).use {
        it.write(body)
    }

    private fun createMatcher(storeFile: String, request: Request, response: Response): List<Matcher> {
        val requestFile = File(rootFolder, storeFile)
        val previousRecords: List<Matcher> = if (requestFile.exists())
            mapper.readMatches(requestFile).toMutableList()
        else emptyList()
        return previousRecords + Matcher(
            request.toDescriptor(),
            response.toDescriptor(previousRecords.size, getExtension(response.body()?.contentType()))
        )
    }

    private fun saveRequestFile(requestFile: File, matchers: List<Matcher>) {
        openFile(requestFile).use {
            mapper.writeValue(it, matchers)
        }
    }

    private fun openFile(file: File): FileOutputStream {
        createParent(file.parentFile)
        return FileOutputStream(file)
    }

    private fun createParent(file: File?) {
        if (file?.parentFile?.exists() == false) {
            createParent(file.parentFile)
            file.mkdir()
        } else if (file?.exists() == false) {
            file.mkdir()
        }
    }

    private fun messageForHttpCode(httpCode: Int) = HTTP_RESPONSES_CODE[httpCode] ?: error("Unknown error code")

    private fun getExtension(contentType: MediaType?) = extensionMappings[contentType.toString()] ?: ".txt"

    /**
     * Defines the interceptor's state and how it is supposed to respond to requests (intercept them, let them through or record them)
     */
    enum class Mode {
        /** lets every request through without interception. */
        DISABLED,
        /** intercepts all requests and return responses found in a predefined configuration */
        ENABLED,
        /** allows to look for responses locally, but execute the request if no response is found */
        MIXED,
        /** allows to record actual requests and responses for future use as mock scenarios */
        RECORD
    }

    /**
     * Builder to instantiate an interceptor.
     */
    class Builder {
        private var filingPolicy: FilingPolicy? = null
        private var openFile: LoadFile? = null
        private var mapper: Mapper? = null
        private var root: File? = null
        private var simulatedDelay: Long = 0
        private var interceptorMode: Mode = Mode.DISABLED

        /**
         * Defines the policy used to retrieve the configuration files based on the request being intercepted
         */
        fun decodeScenarioPathWith(policy: FilingPolicy) = apply {
            filingPolicy = policy
        }

        /**
         * Defines a loading function to retrieve the scenario files as a stream
         */
        fun loadFileWith(loading: LoadFile) = apply {
            openFile = loading
        }

        /**
         * Defines the mapper to use to parse the scenario files (Jackson, Moshi, GSON...)
         */
        fun parseScenariosWith(objectMapper: Mapper) = apply {
            mapper = objectMapper
        }

        /**
         * Defines the folder where scenarios should be stored when recording
         */
        fun saveScenariosIn(folder: File) = apply {
            root = folder
        }

        /**
         * Allows to set a fake delay for every requests (can be overridden in a scenario) to achieve a more realistic
         * behavior (probably necessary if you want to display loading animations during your network calls).
         */
        fun addFakeNetworkDelay(delay: Long) = apply {
            simulatedDelay = delay
        }

        /**
         * Defines how the interceptor should initially behave (can be enabled, disable, record requests...)
         */
        fun setInterceptorStatus(status: Mode) = apply {
            interceptorMode = status
        }

        /**
         * Build the interceptor.
         */
        fun build(): MockResponseInterceptor {
            val policy = filingPolicy
                ?: error(NO_POLICY_ERROR)
            val open = openFile ?: error(NO_LOADER_ERROR)
            val objectMapper = mapper
                ?: error(NO_MAPPER_ERROR)
            return MockResponseInterceptor(policy, open, objectMapper).apply {
                if (interceptorMode == Mode.RECORD && root == null) {
                    error(NO_FOLDER_ERROR)
                }
                rootFolder = root
                delay = simulatedDelay
                mode = interceptorMode
            }
        }
    }
}

/**
 * A loading function that takes a path as input and returns an InputStream to read from. Typical implementations can use
 * FileInputStream instantiations, Classloader.getResourceAsStream call or use of the AssetManager on Android.
 */
typealias LoadFile = (String) -> InputStream?

private const val NO_POLICY_ERROR =
    "No filing policy available. Please specify a FilingPolicy to use to access scenario files."

private const val NO_LOADER_ERROR = "No method has been provided to load the scenarios."

private const val NO_MAPPER_ERROR =
    "No mapper has been provided to deserialize scenarios. Please specify a Mapper to decode the scenario files."

private const val NO_FOLDER_ERROR =
    "Network calls can not be recorded without a folder where to save files. Please add a root folder."

private val HTTP_RESPONSES_CODE = mapOf(
    200 to "OK",
    201 to "Created",
    204 to "No Content",
    302 to "Found",
    400 to "Bad Request",
    403 to "Forbidden",
    404 to "Not Found",
    500 to "Internal Server Error",
    502 to "Bad Gateway",
    503 to "Service unavailable"
)
