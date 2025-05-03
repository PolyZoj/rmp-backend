package common.exceptions

class DuplicateFieldException(field: String) : Exception("Duplicate value for field: $field") {
    val fieldName: String = field
}

class ArgumentNotFoundException(field: String) : Exception("Argument not found: $field") {
    val fieldName: String = field
}
