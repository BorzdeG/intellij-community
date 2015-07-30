/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea.analysis

import org.jetbrains.kotlin.idea.caches.resolve.getResolutionFacade
import org.jetbrains.kotlin.idea.caches.resolve.getService
import org.jetbrains.kotlin.psi.JetExpression
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.resolve.BindingTraceContext
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowInfo
import org.jetbrains.kotlin.resolve.scopes.JetScope
import org.jetbrains.kotlin.types.JetType
import org.jetbrains.kotlin.types.TypeUtils
import org.jetbrains.kotlin.types.expressions.ExpressionTypingServices
import org.jetbrains.kotlin.types.expressions.JetTypeInfo

jvmOverloads
public fun JetExpression.computeTypeInfoInContext(
        scope: JetScope,
        trace: BindingTrace = BindingTraceContext(),
        dataFlowInfo: DataFlowInfo = DataFlowInfo.EMPTY,
        expectedType: JetType = TypeUtils.NO_EXPECTED_TYPE
): JetTypeInfo {
    return getResolutionFacade().getService<ExpressionTypingServices>(this).getTypeInfo(scope, this, expectedType, dataFlowInfo, trace)
}

jvmOverloads
public fun JetExpression.analyzeInContext(
        scope: JetScope,
        trace: BindingTrace = BindingTraceContext(),
        dataFlowInfo: DataFlowInfo = DataFlowInfo.EMPTY,
        expectedType: JetType = TypeUtils.NO_EXPECTED_TYPE
): BindingContext {
    computeTypeInfoInContext(scope, trace, dataFlowInfo, expectedType)
    return trace.getBindingContext()
}
