package com.openandroidintelligence.core.model

enum class NotificationDeliveryMode { ON_DEMAND, AUTO_SEND }

fun compareNotificationPackageIds(left: String, right: String): Int {
    val leftCodePoints = left.codePoints().toArray()
    val rightCodePoints = right.codePoints().toArray()
    val commonLength = minOf(leftCodePoints.size, rightCodePoints.size)

    for (index in 0 until commonLength) {
        leftCodePoints[index].compareTo(rightCodePoints[index]).let { comparison ->
            if (comparison != 0) return comparison
        }
    }

    return leftCodePoints.size.compareTo(rightCodePoints.size)
}

fun sortNotificationPackageIds(values: Iterable<String>): List<String> {
    val packageIds = values.toList()
    require(packageIds.size == packageIds.toSet().size) { "package IDs must be unique" }
    return packageIds.sortedWith(::compareNotificationPackageIds)
}
