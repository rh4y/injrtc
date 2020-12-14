package com.ivianuu.injekt.compiler.resolution

sealed class GivenGraph {
    abstract val requests: List<GivenRequest>

    data class Success(
        override val requests: List<GivenRequest>,
        val scope: ResolutionScope,
        val givens: Map<GivenRequest, GivenNode>,
    ) : GivenGraph()

    data class Error(
        override val requests: List<GivenRequest>,
        val failures: Map<GivenRequest, List<ResolutionResult.Failure>>,
    ) : GivenGraph()
}

sealed class CandidateResolutionResult {
    abstract val request: GivenRequest
    abstract val candidate: GivenNode

    data class Success(
        override val request: GivenRequest,
        override val candidate: GivenNode,
        val dependencyResults: List<ResolutionResult.Success>,
    ) : CandidateResolutionResult()

    data class Failure(
        override val request: GivenRequest,
        override val candidate: GivenNode,
        val failure: ResolutionResult.Failure,
    ) : CandidateResolutionResult()
}

sealed class ResolutionResult {
    abstract val request: GivenRequest

    data class Success(
        override val request: GivenRequest,
        val candidateResult: CandidateResolutionResult.Success,
    ) : ResolutionResult()

    sealed class Failure : ResolutionResult() {
        abstract val failureOrdering: Int

        data class CandidateAmbiguity(
            override val request: GivenRequest,
            val candidateResults: List<CandidateResolutionResult.Success>,
        ) : Failure() {
            override val failureOrdering: Int
                get() = 0
        }

        data class CallContextMismatch(
            override val request: GivenRequest,
            val candidate: GivenNode,
        ) : Failure() {
            override val failureOrdering: Int
                get() = 1
        }

        data class DivergentGiven(override val request: GivenRequest) : Failure() {
            override val failureOrdering: Int
                get() = 1
        }

        data class CircularDependency(
            override val request: GivenRequest,
            val chain: List<GivenRequest>,
        ) : Failure() {
            override val failureOrdering: Int
                get() = 1
        }

        data class CandidateFailures(
            override val request: GivenRequest,
            val candidateFailure: CandidateResolutionResult.Failure,
        ) : Failure() {
            override val failureOrdering: Int
                get() = 1
        }

        data class NoCandidates(override val request: GivenRequest) : Failure() {
            override val failureOrdering: Int
                get() = 2
        }
    }
}

fun ResolutionScope.resolveGiven(requests: List<GivenRequest>): GivenGraph {
    val context = ResolutionContext(this)
    val (successResults, failureResults) = requests
        .map { resolveRequest(context, it) }
        .let {
            it
                .filterIsInstance<ResolutionResult.Success>() to
                    it.filterIsInstance<ResolutionResult.Failure>()
        }
    return if (failureResults.isEmpty()) {
        successResults.toSuccessGraph(this, requests)
    } else failureResults.toErrorGraph(requests)
}

private fun ResolutionScope.resolveRequest(
    context: ResolutionContext,
    request: GivenRequest,
): ResolutionResult = context.resolveInScope(
    request,
    context.getProvidedCandidates(request) +
            givensForType(request.type) +
            getFrameworkCandidates(request)
)

private fun List<ResolutionResult.Success>.toSuccessGraph(
    scope: ResolutionScope,
    requests: List<GivenRequest>,
): GivenGraph.Success {
    val givensByRequest = mutableMapOf<GivenRequest, GivenNode>()
    fun ResolutionResult.Success.visit() {
        givensByRequest[request] = candidateResult.candidate
        candidateResult.dependencyResults
            .forEach { it.visit() }
    }
    forEach { it.visit() }
    return GivenGraph.Success(requests, scope, givensByRequest)
}

private fun List<ResolutionResult.Failure>.toErrorGraph(
    requests: List<GivenRequest>,
): GivenGraph.Error {
    val failuresByRequest = mutableMapOf<GivenRequest, MutableList<ResolutionResult.Failure>>()
    fun ResolutionResult.Failure.visit() {
        failuresByRequest.getOrPut(request) { mutableListOf() } += this
    }
    forEach { it.visit() }
    return GivenGraph.Error(requests, failuresByRequest)
}

class ResolutionContext(val scope: ResolutionScope) {
    private val chain = mutableSetOf<GivenNode>()
    private val resultsByCandidate = mutableMapOf<GivenNode, CandidateResolutionResult>()

    private val resultsByRequest = mutableMapOf<GivenRequest, ResolutionResult>()

    val providedGivens = mutableListOf<GivenNode>()

    fun computeForRequest(
        request: GivenRequest,
        compute: () -> ResolutionResult,
    ) = resultsByRequest.getOrPut(request, compute)

    fun computeForCandidate(
        request: GivenRequest,
        candidate: GivenNode,
        compute: () -> CandidateResolutionResult,
    ): CandidateResolutionResult = resultsByCandidate.getOrPut(candidate) {
        chain.reversed().forEach { prev ->
            if (prev.callableFqName == candidate.callableFqName &&
                prev.type.coveringSet == candidate.type.coveringSet &&
                candidate.type.typeSize > prev.type.typeSize
            ) {
                return@getOrPut CandidateResolutionResult.Failure(
                    request,
                    candidate,
                    ResolutionResult.Failure.DivergentGiven(request)
                )
            }
        }

        if (candidate in chain) {
            val chainList = chain.toList()
            val cycleChain = chainList.subList(chainList.indexOf(candidate), chainList.size)
            return@getOrPut CandidateResolutionResult.Failure(
                request,
                candidate,
                ResolutionResult.Failure.CircularDependency(request, emptyList()) // todo
            )
        }

        chain += candidate
        providedGivens += candidate.providedGivens
        val result = compute()
        chain -= candidate
        providedGivens -= candidate.providedGivens
        return@getOrPut result
    }
}

private fun ResolutionContext.resolveInScope(
    request: GivenRequest,
    candidates: List<GivenNode>,
): ResolutionResult = computeForRequest(request) {
    if (candidates.isEmpty()) return@computeForRequest ResolutionResult.Failure.NoCandidates(request)
    if (candidates.size == 1) {
        val candidate = candidates.single()
        return@computeForRequest when (val candidateResult = resolveCandidate(request, candidate)) {
            is CandidateResolutionResult.Success ->
                ResolutionResult.Success(request, candidateResult)
            is CandidateResolutionResult.Failure ->
                ResolutionResult.Failure.CandidateFailures(request, candidateResult)
        }
    }

    val (successResults, failureResults) = candidates
        .map { resolveCandidate(request, it) }
        .let {
            it.filterIsInstance<CandidateResolutionResult.Success>() to
                    it.filterIsInstance<CandidateResolutionResult.Failure>()
        }

    return@computeForRequest if (successResults.isNotEmpty()) {
        successResults
            .disambiguate()
            .let { finalResults ->
                finalResults.singleOrNull()?.let {
                    ResolutionResult.Success(request, it)
                } ?: ResolutionResult.Failure.CandidateAmbiguity(request, finalResults)
            }
    } else {
        ResolutionResult.Failure.CandidateFailures(request, failureResults.first())
    }
}

private fun ResolutionContext.resolveCandidate(
    request: GivenRequest,
    candidate: GivenNode,
): CandidateResolutionResult = computeForCandidate(request, candidate) {
    if (!request.callContext.canCall(candidate.callContext)) {
        return@computeForCandidate CandidateResolutionResult.Failure(
            request,
            candidate,
            ResolutionResult.Failure.CallContextMismatch(request, candidate)
        )
    }

    val successDependencyResults = mutableListOf<ResolutionResult.Success>()
    for (dependency in candidate.dependencies) {
        when (val result = scope.resolveRequest(this, dependency)) {
            is ResolutionResult.Success -> successDependencyResults += result
            is ResolutionResult.Failure -> return@computeForCandidate CandidateResolutionResult.Failure(
                dependency,
                candidate,
                result)
        }
    }
    return@computeForCandidate CandidateResolutionResult.Success(request,
        candidate,
        successDependencyResults)
}

private fun ResolutionContext.getProvidedCandidates(request: GivenRequest): List<GivenNode> {
    return providedGivens
        .reversed()
        .distinctBy { it.type }
        .reversed()
        .filter { it.type.isAssignableTo(request.type) }
}

private fun ResolutionScope.getFrameworkCandidates(request: GivenRequest): List<GivenNode> {
    if (request.type.classifier.fqName.asString().startsWith("kotlin.Function")
        || request.type.classifier.fqName.asString().startsWith("kotlin.coroutines.SuspendFunction")
    ) {
        return listOf(
            ProviderGivenNode(
                request.type,
                Int.MAX_VALUE,
                declarationStore,
                request.required
            )
        )
    }

    val setType = declarationStore.module.builtIns.set.defaultType.toTypeRef()
    if (request.type.isSubTypeOf(setType)) {
        val setElementType = request.type.subtypeView(setType.classifier)!!.typeArguments.single()
        val elements = givenSetElementsForType(setElementType)
        return listOf(
            CollectionGivenNode(
                request.type,
                Int.MAX_VALUE,
                elements,
                elements
                    .flatMap { element ->
                        element.getGivenRequests(request.type, declarationStore)
                    }
            )
        )
    }

    return emptyList()
}

private fun prefer(
    a: CandidateResolutionResult.Success?,
    b: CandidateResolutionResult.Success?,
): Int {
    if (a != null && b == null) return -1
    if (b != null && a == null) return 1
    if (a == null && b == null) return 0
    a!!
    b!!
    if (a.candidate.depth < b.candidate.depth) return -1
    if (b.candidate.depth < a.candidate.depth) return 1
    if (a.dependencyResults.size < b.dependencyResults.size) return -1
    if (b.dependencyResults.size < a.dependencyResults.size) return 1

    // todo
    return 0
}

private fun List<CandidateResolutionResult.Success>.disambiguate(): List<CandidateResolutionResult.Success> {
    if (size <= 1) return this
    val results = mutableListOf<CandidateResolutionResult.Success>()
    forEach { result ->
        when (prefer(results.lastOrNull(), result)) {
            -1 -> {
            } // do nothing previous result was better
            1 -> {
                results.clear()
                results += result
            }
            0 -> results += result
        }
    }
    return results
}
