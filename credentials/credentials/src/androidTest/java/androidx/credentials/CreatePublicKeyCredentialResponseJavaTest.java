/*
 * Copyright 2022 The Android Open Source Project
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

package androidx.credentials;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class CreatePublicKeyCredentialResponseJavaTest {

    @Test
    public void constructor_emptyJson_throwsIllegalArgumentException() {
        assertThrows("Expected empty Json to throw error",
                IllegalArgumentException.class,
                () -> new CreatePublicKeyCredentialResponse("")
        );
    }

    @Test
    public void constructor_nullJson_throwsNullPointerException() {
        assertThrows("Expected null Json to throw NullPointerException",
                NullPointerException.class,
                () -> new CreatePublicKeyCredentialResponse(null)
        );
    }

    @Test
    public void constructor_success()  {
        new CreatePublicKeyCredentialResponse("{\"hi\":1}");
    }

    @Test
    public void getter_registrationResponseJson_success() {
        String testJsonExpected = "{\"input\":5}";
        CreatePublicKeyCredentialResponse createPublicKeyCredentialResponse =
                new CreatePublicKeyCredentialResponse(testJsonExpected);
        String testJsonActual = createPublicKeyCredentialResponse.getRegistrationResponseJson();
        assertThat(testJsonActual).isEqualTo(testJsonExpected);
    }
}
