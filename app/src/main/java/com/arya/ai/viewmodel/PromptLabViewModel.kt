package com.arya.ai.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PromptTemplate(val title: String, val description: String, val promptPrefix: String)

val PROMPT_TEMPLATES = listOf(
    PromptTemplate("Summarize", "Lambe text ko chhota karo", "Summarize the following text in 3-4 concise sentences:\n\n"),
    PromptTemplate("Rewrite formally", "Casual text ko formal banao", "Rewrite the following text in a formal, professional tone:\n\n"),
    PromptTemplate("Brainstorm ideas", "Ek topic pe ideas nikalo", "Brainstorm 5 creative ideas about:\n\n"),
    PromptTemplate("Explain simply", "Kisi cheez ko simple bhasha me samjhao", "Explain the following like I'm a beginner, in simple words:\n\n"),
    PromptTemplate("Fix grammar", "Grammar/spelling theek karo", "Fix grammar and spelling in the following text, keep the meaning same:\n\n"),
    PromptTemplate("Translate to Hindi", "English ko Hindi me convert karo", "Translate the following text to Hindi:\n\n")
)

/** @param generateOnline Calls the free online relay with a full prompt, returns the reply text. */
class PromptLabViewModel(private val generateOnline: suspend (String) -> String) : ViewModel() {

    private val _output = MutableStateFlow("")
    val output: StateFlow<String> = _output.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    fun run(template: PromptTemplate, input: String) {
        if (input.isBlank() || _isGenerating.value) return
        _output.value = ""
        _isGenerating.value = true
        viewModelScope.launch {
            try {
                _output.value = generateOnline(template.promptPrefix + input)
            } catch (e: Exception) {
                _output.value = "⚠️ Error: ${e.message}"
            } finally {
                _isGenerating.value = false
            }
        }
    }

    fun clear() { _output.value = "" }
}
