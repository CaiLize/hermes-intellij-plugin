package com.hermes.intellij.settings

import com.hermes.intellij.api.HermesApiClient
import com.hermes.intellij.services.HermesSettingsState
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

class HermesSettingsConfigurable : Configurable {

    private var endpointField: JBTextField? = null
    private var apiKeyField: JBTextField? = null
    private var modelField: JBTextField? = null
    private var mainPanel: JPanel? = null

    override fun getDisplayName(): String = "Hermes AI"

    override fun createComponent(): JComponent {
        endpointField = JBTextField(40)
        apiKeyField = JBTextField(40)
        modelField = JBTextField(40)

        val testButton = JButton("Test Connection").apply {
            addActionListener { onTestConnection() }
        }

        mainPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("API Endpoint:"), endpointField!!, 1, false)
            .addLabeledComponent(JBLabel("API Key:"), apiKeyField!!, 1, false)
            .addLabeledComponent(JBLabel("Model:"), modelField!!, 1, false)
            .addComponent(testButton)
            .addComponentFillVertically(JPanel(), 0)
            .panel.apply {
                border = JBUI.Borders.empty(10)
            }

        reset()
        return mainPanel!!
    }

    override fun isModified(): Boolean {
        val settings = HermesSettingsState.getInstance()
        return (endpointField?.text ?: "") != settings.apiEndpoint ||
                (apiKeyField?.text ?: "") != settings.apiKey ||
                (modelField?.text ?: "") != settings.modelName
    }

    override fun apply() {
        val settings = HermesSettingsState.getInstance()
        settings.apiEndpoint = endpointField?.text ?: settings.apiEndpoint
        settings.apiKey = apiKeyField?.text ?: settings.apiKey
        settings.modelName = modelField?.text ?: settings.modelName
    }

    override fun reset() {
        val settings = HermesSettingsState.getInstance()
        endpointField?.text = settings.apiEndpoint
        apiKeyField?.text = settings.apiKey
        modelField?.text = settings.modelName
    }

    override fun disposeUIResources() {
        endpointField = null
        apiKeyField = null
        modelField = null
        mainPanel = null
    }

    private fun onTestConnection() {
        // Temporarily apply field values for testing
        val settings = HermesSettingsState.getInstance()
        val origEndpoint = settings.apiEndpoint
        val origKey = settings.apiKey
        val origModel = settings.modelName

        try {
            settings.apiEndpoint = endpointField?.text ?: origEndpoint
            settings.apiKey = apiKeyField?.text ?: origKey
            settings.modelName = modelField?.text ?: origModel

            val success = HermesApiClient.getInstance().testConnection()

            if (success) {
                Messages.showInfoMessage("Connection successful!", "Hermes AI")
            } else {
                Messages.showErrorDialog(
                    "Cannot connect to Hermes API. Please check the endpoint and ensure hermes gateway is running.",
                    "Hermes AI"
                )
            }
        } catch (e: Exception) {
            Messages.showErrorDialog(
                "Connection error: ${e.message}",
                "Hermes AI"
            )
        } finally {
            settings.apiEndpoint = origEndpoint
            settings.apiKey = origKey
            settings.modelName = origModel
        }
    }
}
