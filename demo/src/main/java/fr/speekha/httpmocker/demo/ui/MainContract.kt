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

package fr.speekha.httpmocker.demo.ui

import androidx.annotation.IntegerRes
import fr.speekha.httpmocker.MockResponseInterceptor
import fr.speekha.httpmocker.demo.model.Repo

interface MainContract {

    interface View {
        fun setResult(result: List<Repo>)
        fun setError(message: String?)
        fun checkPermission()
        fun updateDescriptionLabel(@IntegerRes resId: Int)
    }

    interface Presenter {
        fun stop()
        fun callService()
        fun setMode(mode: MockResponseInterceptor.Mode)
    }
}