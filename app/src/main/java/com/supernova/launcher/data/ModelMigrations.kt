package com.supernova.launcher.data

object ModelMigrations {

    fun migrate(model: LauncherModel): LauncherModel {
        var current = model
        while (current.version < CURRENT_MODEL_VERSION) {
            current = when (current.version) {
                1 -> migrate1To2(current)
                2 -> migrate2To3(current)
                3 -> migrate3To4(current)
                4 -> migrate4To5(current)
                else -> current.copy(version = CURRENT_MODEL_VERSION)
            }
        }
        return current
    }

    // v2 added LauncherItem.userSerial; the serialization default already stamps
    // primary-user 0 on decode, so this step only records the new version.
    private fun migrate1To2(model: LauncherModel): LauncherModel = model.copy(version = 2)

    // v4 added appOverrides and folder custom-icon fields; serialization
    // defaults supply empty values, so this step only records the version.
    private fun migrate3To4(model: LauncherModel): LauncherModel = model.copy(version = 4)

    // v5 added feedCards; the serialization default supplies the empty list,
    // so this step only records the version.
    private fun migrate4To5(model: LauncherModel): LauncherModel = model.copy(version = 5)

    // v3 added explicit pageCount/defaultPage; derive the count from the pages
    // items actually occupy.
    private fun migrate2To3(model: LauncherModel): LauncherModel {
        val derivedPages = model.items
            .filter { it.container == ContainerType.WORKSPACE }
            .maxOfOrNull { it.page + 1 } ?: 1
        return model.copy(
            version = 3,
            pageCount = maxOf(1, derivedPages, model.pageCount),
            defaultPage = 0
        )
    }
}
