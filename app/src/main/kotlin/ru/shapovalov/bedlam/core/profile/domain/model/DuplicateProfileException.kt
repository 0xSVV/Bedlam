package ru.shapovalov.bedlam.core.profile.domain.model

class DuplicateProfileException(val existingName: String) :
    Exception("Profile already saved as \"$existingName\"")
