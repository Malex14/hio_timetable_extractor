package de.mbehrmann.hio_timetable_extractor

class HTTPException: RuntimeException {
    constructor(): super()

    constructor(message: String?): super(message)

    constructor(message: String?, cause: Throwable?) : super(message, cause)

    constructor(cause: Throwable?) : super(cause)
}