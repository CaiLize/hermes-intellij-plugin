package com.hermes.intellij.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(
    name = "HermesSettings",
    storages = [Storage("hermes-ai.xml")]
)
@Service(Service.Level.APP)
class HermesSettingsState : PersistentStateComponent<HermesSettingsState.State> {

    data class State(
        var apiEndpoint: String = "http://127.0.0.1:8642/v1",
        var modelName: String = "hermes-agent",
        var apiKey: String = ""
    )

    private var myState = State()

    var apiEndpoint: String
        get() = myState.apiEndpoint
        set(value) { myState.apiEndpoint = value }

    var modelName: String
        get() = myState.modelName
        set(value) { myState.modelName = value }

    var apiKey: String
        get() = myState.apiKey
        set(value) { myState.apiKey = value }

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        fun getInstance(): HermesSettingsState =
            ApplicationManager.getApplication().getService(HermesSettingsState::class.java)
    }
}
