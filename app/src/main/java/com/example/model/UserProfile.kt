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
    val avatarUri: String? = null,
    val gender: String? = null,
    val dob: String? = null,
    val countryName: String? = null,
    val countryCode: String? = null
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

    fun updateGender(gender: String) {
        _profile.value = _profile.value.copy(gender = gender)
    }

    fun updateDob(dob: String) {
        _profile.value = _profile.value.copy(dob = dob)
    }

    fun updateCountry(name: String, code: String) {
        _profile.value = _profile.value.copy(countryName = name, countryCode = code)
    }

    fun setUser(name: String, email: String, id: String = "") {
        _profile.value = _profile.value.copy(name = name, email = email, id = id)
    }

    /**
     * Bulk-update all profile fields from a server-fetched profile row.
     * Used by ProfileService.refreshFromServer() to hydrate the in-memory
     * state after login / OTP verification / app launch.
     */
    fun updateAll(
        id: String = _profile.value.id,
        name: String = _profile.value.name,
        email: String = _profile.value.email,
        username: String = _profile.value.username,
        about: String = _profile.value.about,
        avatarUri: String? = _profile.value.avatarUri,
        gender: String? = _profile.value.gender,
        dob: String? = _profile.value.dob,
        countryName: String? = _profile.value.countryName,
        countryCode: String? = _profile.value.countryCode,
        links: String = _profile.value.links
    ) {
        _profile.value = UserProfile(
            id = id,
            name = name,
            email = email,
            username = username,
            about = about,
            avatarUri = avatarUri,
            gender = gender,
            dob = dob,
            countryName = countryName,
            countryCode = countryCode,
            links = links
        )
    }

    fun clear() {
        _profile.value = UserProfile()
    }
}
