package au.com.dius.pact.provider

import au.com.dius.pact.com.github.michaelbull.result.Ok
import au.com.dius.pact.model.FilteredPact
import au.com.dius.pact.model.Interaction
import au.com.dius.pact.model.Pact
import au.com.dius.pact.model.PactReader
import au.com.dius.pact.model.RequestResponseInteraction
import au.com.dius.pact.model.Response
import au.com.dius.pact.model.UrlPactSource
import au.com.dius.pact.provider.broker.PactBrokerClient
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import scala.Function1

import java.util.function.Function
import java.util.function.Predicate

/**
 * Verifies the providers against the defined consumers in the context of a build plugin
 */
@Slf4j
@SuppressWarnings('ConfusingMethodName')
class ProviderVerifier extends ProviderVerifierBase {

  Map verifyProvider(ProviderInfo provider) {
    Map failures = [:]

    initialiseReporters(provider)

    def consumers = provider.consumers.findAll(this.&filterConsumers)
    if (consumers.empty) {
      reporters.each { it.warnProviderHasNoConsumers(provider) }
    }

    consumers.each(this.&runVerificationForConsumer.curry(failures, provider))

    failures
  }

  void initialiseReporters(ProviderInfo provider) {
    reporters.each {
      if (it.hasProperty('displayFullDiff')) {
        it.displayFullDiff = projectHasProperty.apply(PACT_SHOW_FULLDIFF)
      }
      it.initialise(provider)
    }
  }

  @CompileStatic
  void runVerificationForConsumer(Map failures, ProviderInfo provider, ConsumerInfo consumer,
                                  PactBrokerClient client = null) {
    reportVerificationForConsumer(consumer, provider)
    FilteredPact pact = new FilteredPact(loadPactFileForConsumer(consumer),
      this.&filterInteractions as Predicate<Interaction>)
    if (pact.interactions.empty) {
      reporters.each { it.warnPactFileHasNoInteractions(pact) }
    } else {
      boolean result = pact.interactions
        .collect(this.&verifyInteraction.curry(provider, consumer, failures))
        .inject(true) { acc, val -> acc && val }
      if (pact.isFiltered()) {
        log.warn('Skipping publishing of verification results as the interactions have been filtered')
      } else if (publishingResultsDisabled()) {
        log.warn('Skipping publishing of verification results as it has been disabled ' +
          "(${PACT_VERIFIER_PUBLISH_RESULTS} is not 'true')")
      } else {
        verificationReporter.reportResults(pact, result, providerVersion?.get() ?: '0.0.0', client)
      }
    }
  }

  void reportVerificationForConsumer(ConsumerInfo consumer, ProviderInfo provider) {
    reporters.each { it.reportVerificationForConsumer(consumer, provider) }
  }

  @SuppressWarnings('ThrowRuntimeException')
  Pact<? extends Interaction> loadPactFileForConsumer(IConsumerInfo consumer) {
    def pactSource = consumer.pactSource
    if (pactSource instanceof Closure) {
      pactSource = pactSource.call()
    }

    if (pactSource instanceof UrlPactSource) {
      reporters.each { it.verifyConsumerFromUrl(pactSource, consumer) }
      def options = [:]
      if (consumer.pactFileAuthentication) {
        options.authentication = consumer.pactFileAuthentication
      }
      PactReader.loadPact(options, pactSource)
    } else {
      try {
        def pact = PactReader.loadPact(pactSource)
        reporters.each { it.verifyConsumerFromFile(pact.source, consumer) }
        pact
      } catch (e) {
        log.error('Failed to load pact file', e)
        String message = generateLoadFailureMessage(consumer)
        reporters.each { it.pactLoadFailureForConsumer(consumer, message) }
        throw new RuntimeException(message)
      }
    }
  }

  private generateLoadFailureMessage(IConsumerInfo consumer) {
    if (pactLoadFailureMessage instanceof Closure) {
      pactLoadFailureMessage.call(consumer) as String
    } else if (pactLoadFailureMessage instanceof Function) {
      pactLoadFailureMessage.apply(consumer) as String
    } else if (pactLoadFailureMessage instanceof Function1) {
      pactLoadFailureMessage.apply(consumer) as String
    } else {
      pactLoadFailureMessage as String
    }
  }

  boolean filterConsumers(def consumer) {
    !projectHasProperty.apply(PACT_FILTER_CONSUMERS) ||
      consumer.name in projectGetProperty.apply(PACT_FILTER_CONSUMERS).split(',')*.trim()
  }

  boolean filterInteractions(def interaction) {
    if (projectHasProperty.apply(PACT_FILTER_DESCRIPTION) && projectHasProperty.apply(PACT_FILTER_PROVIDERSTATE)) {
      matchDescription(interaction) && matchState(interaction)
    } else if (projectHasProperty.apply(PACT_FILTER_DESCRIPTION)) {
      matchDescription(interaction)
    } else if (projectHasProperty.apply(PACT_FILTER_PROVIDERSTATE)) {
      matchState(interaction)
    } else {
      true
    }
  }

  private boolean matchState(interaction) {
    if (interaction.providerStates) {
      interaction.providerStates.any { it.name ==~ projectGetProperty.apply(PACT_FILTER_PROVIDERSTATE) }
    } else {
      projectGetProperty.apply(PACT_FILTER_PROVIDERSTATE).empty
    }
  }

  private boolean matchDescription(interaction) {
    interaction.description ==~ projectGetProperty.apply(PACT_FILTER_DESCRIPTION)
  }

  boolean verifyInteraction(ProviderInfo provider, ConsumerInfo consumer, Map failures, def interaction) {
    def interactionMessage = "Verifying a pact between ${consumer.name} and ${provider.name}" +
      " - ${interaction.description} "

    ProviderClient providerClient = new ProviderClient(provider, new HttpClientFactory())
    def stateChangeResult = StateChange.executeStateChange(this, provider, consumer, interaction, interactionMessage,
      failures, providerClient)
    if (stateChangeResult.stateChangeResult instanceof Ok) {
      interactionMessage = stateChangeResult.message
      reportInteractionDescription(interaction)

      Map context = [
        providerState: stateChangeResult.stateChangeResult.value,
        interaction: interaction
      ]

      boolean result = false
      if (ProviderUtils.verificationType(provider, consumer) == PactVerification.REQUEST_RESPONSE) {
        log.debug('Verifying via request/response')
        result = verifyResponseFromProvider(provider, interaction, interactionMessage, failures, providerClient,
          context)
      } else {
        log.debug('Verifying via annotated test method')
        result = verifyResponseByInvokingProviderMethods(provider, consumer, interaction, interactionMessage, failures)
      }

      if (provider.stateChangeTeardown) {
        StateChange.executeStateChangeTeardown(this, interaction, provider, consumer, providerClient)
      }

      result
    } else {
      false
    }
  }

  void reportInteractionDescription(interaction) {
    reporters.each { it.interactionDescription(interaction) }
  }

  @Override
  void reportStateForInteraction(String state, IProviderInfo provider, IConsumerInfo consumer, boolean isSetup) {
    reporters.each { it.stateForInteraction(state, provider, consumer, isSetup) }
  }

  @SuppressWarnings('ParameterCount')
  boolean verifyResponseFromProvider(IProviderInfo provider, RequestResponseInteraction interaction,
                                     String interactionMessage,
                                     Map failures,
                                     ProviderClient client,
                                     Map context = [:]) {
    try {
      def expectedResponse = interaction.response.generatedResponse(context)
      def actualResponse = client.makeRequest(interaction.request.generatedRequest(context))

      verifyRequestResponsePact(expectedResponse, actualResponse, interactionMessage, failures)
    } catch (e) {
      failures[interactionMessage] = e
      reporters.each {
        it.requestFailed(provider, interaction, interactionMessage, e, projectHasProperty.apply(PACT_SHOW_STACKTRACE))
      }
      false
    }
  }

  boolean verifyRequestResponsePact(Response expectedResponse, Map actualResponse, String interactionMessage,
                                 Map failures) {
    def comparison = ResponseComparison.compareResponse(expectedResponse, actualResponse,
      actualResponse.statusCode, actualResponse.headers, actualResponse.data)

    reporters.each { it.returnsAResponseWhich() }

    def s = ' returns a response which'
    def result = true
    result &= displayStatusResult(failures, expectedResponse.status, comparison.method, interactionMessage + s)
    result &= displayHeadersResult(failures, expectedResponse.headers, comparison.headers, interactionMessage + s)
    result &= displayBodyResult(failures, comparison.body, interactionMessage + s)
    result
  }

  boolean displayStatusResult(Map failures, int status, def comparison, String comparisonDescription) {
    if (comparison == null) {
      reporters.each { it.statusComparisonOk(status) }
      true
    } else {
      reporters.each { it.statusComparisonFailed(status, comparison) }
      failures["$comparisonDescription has status code $status"] = comparison
      false
    }
  }

  boolean displayHeadersResult(Map failures, def expected, Map comparison, String comparisonDescription) {
    if (comparison.isEmpty()) {
      true
    } else {
      reporters.each { it.includesHeaders() }
      Map expectedHeaders = expected
      boolean result = true
      comparison.each { key, headerComparison ->
        def expectedHeaderValue = expectedHeaders[key]
        if (headerComparison == null) {
          reporters.each { it.headerComparisonOk(key, expectedHeaderValue) }
        } else {
          reporters.each { it.headerComparisonFailed(key, expectedHeaderValue, headerComparison) }
          failures["$comparisonDescription includes headers \"$key\" with value \"$expectedHeaderValue\""] =
            headerComparison
          result = false
        }
      }
      result
    }
  }
}
