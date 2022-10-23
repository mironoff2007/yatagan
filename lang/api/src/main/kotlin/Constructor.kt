package com.yandex.daggerlite.lang

/**
 * Models a type constructor.
 */
interface Constructor : Callable, AnnotatedLangModel, Accessible {
    /**
     * An owner and a constructed type of the constructor.
     */
    val constructee: TypeDeclarationLangModel
}