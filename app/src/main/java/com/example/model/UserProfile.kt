package com.example.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UserProfile(
    val id: String = "",
    val name: String = "",
    val email: String = "",
    val username: String = "",
    val about: String = "Hey there! I am using Trigger App.",
    val links: String = "",
    val avatarUri: String? = null
)

object UserRepository {
    private val _profile = MutableStateFlow(
        UserProfile()
    )
    val profile: StateFlow<UserProfile> = _profile.asStateFlow()

    fun updateName(name: String) {
        _profile.value = _profile.value.copy(name = name)
    }

    fun updateEmail(email: String) {
        _profile.value = _profile.value.copy(email = email)
    }

    fun updateUsername(username: String) {
        _profile.value = _profile.value.copy(username = username)
    }

    fun updateAbout(about: String) {
        _profile.value = _profile.value.copy(about = about)
    }

    fun updateLinks(links: String) {
        _profile.value = _profile.value.copy(links = links)
    }

    fun updateAvatarUri(uri: String?) {
        _profile.value = _profile.value.copy(avatarUri = uri)
    }

    fun setUser(name: String, email: String, id: String = "") {
        _profile.value = _profile.value.copy(name = name, email = email, id = id)
    }

    fun clear() {
        _profile.value = UserProfile()
    }
}

