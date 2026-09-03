package com.example.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UserProfile(
    val name: String = "Akhil",
    val email: String = "akhil@gmail.com",
    val username: String = "",
    val about: String = "Hey there! I am using Trigger App.",
    val links: String = "",
    val avatarUri: String? = null
)

object UserRepository {
    private val _profile = MutableStateFlow(
        UserProfile(
            name = "Akhil",
            email = "akhil@gmail.com",
            username = "",
            about = "Hey there! I am using Trigger App.",
            links = "",
            avatarUri = null
        )
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

    fun setUser(name: String, email: String) {
        _profile.value = _profile.value.copy(name = name, email = email)
    }
}
