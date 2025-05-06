package ru.polyZoj.exceptions

class DuplicateFieldException(field: String) : Exception("Duplicate value for field: $field") {
    val fieldName: String = field
}