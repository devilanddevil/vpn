package com.jarvis.assistant.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

data class ScreenElement(
    val id: String,
    val text: String,
    val contentDescription: String,
    val className: String,
    val isClickable: Boolean,
    val isCheckable: Boolean,
    val isChecked: Boolean,
    val isEditable: Boolean,
    val bounds: Rect
)

data class ScreenState(
    val packageName: String,
    val visibleElements: List<ScreenElement>,
    val clickableOptions: List<String>,
    val toggleOptions: Map<String, Boolean>,
    val inputFields: List<String>
)

class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "JarvisAccessibility"
        var instance: JarvisAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Jarvis Autonomous Accessibility Service Connected!")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        Log.w(TAG, "Jarvis Accessibility Service Interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    // Global navigation
    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun openRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun openNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun lockScreen(): Boolean = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)

    // Autonomous Screen Inspection
    fun captureCurrentScreenState(): ScreenState {
        val root = rootInActiveWindow
        if (root == null) {
            return ScreenState("unknown", emptyList(), emptyList(), emptyMap(), emptyList())
        }

        val elements = mutableListOf<ScreenElement>()
        val clickableList = mutableListOf<String>()
        val toggleMap = mutableMapOf<String, Boolean>()
        val inputList = mutableListOf<String>()

        traverseNode(root, elements)

        val pkgName = root.packageName?.toString() ?: "unknown"

        for (el in elements) {
            val label = when {
                el.text.isNotBlank() -> el.text
                el.contentDescription.isNotBlank() -> el.contentDescription
                else -> ""
            }

            if (label.isNotBlank()) {
                if (el.isClickable) clickableList.add(label)
                if (el.isCheckable) toggleMap[label] = el.isChecked
                if (el.isEditable) inputList.add(label)
            }
        }

        return ScreenState(
            packageName = pkgName,
            visibleElements = elements,
            clickableOptions = clickableList.distinct(),
            toggleOptions = toggleMap,
            inputFields = inputList.distinct()
        )
    }

    private fun traverseNode(node: AccessibilityNodeInfo?, list: MutableList<ScreenElement>) {
        if (node == null) return

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        // Ignore offscreen elements
        if (bounds.width() > 0 && bounds.height() > 0) {
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val resId = node.viewIdResourceName ?: ""
            val cls = node.className?.toString() ?: ""

            val isEditable = node.className?.toString()?.contains("EditText", ignoreCase = true) == true
            if (text.isNotBlank() || desc.isNotBlank() || node.isClickable || node.isCheckable || isEditable) {
                list.add(
                    ScreenElement(
                        id = resId,
                        text = text.trim(),
                        contentDescription = desc.trim(),
                        className = cls,
                        isClickable = node.isClickable,
                        isCheckable = node.isCheckable,
                        isChecked = node.isChecked,
                        isEditable = isEditable,
                        bounds = bounds
                    )
                )
            }
        }

        for (i in 0 until node.childCount) {
            traverseNode(node.getChild(i), list)
        }
    }

    // Autonomous Click by Target Name or Text
    fun clickElementByText(target: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findMatchingNode(root, target)
        if (node != null) {
            val clicked = performClickOnNode(node)
            if (clicked) {
                ActionHistoryManager.getInstance().recordAction(
                    description = "Clicked on '$target'",
                    actionType = ActionType.CLICK_ELEMENT,
                    packageName = root.packageName?.toString() ?: "",
                    elementIdentifier = target,
                    previousState = "unclicked",
                    newState = "clicked"
                )
                Log.d(TAG, "Successfully clicked '$target'")
                return true
            }
        }
        return false
    }

    // Toggle a setting switch ON or OFF
    fun toggleSettingSwitch(targetName: String, desiredState: Boolean? = null): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findMatchingNode(root, targetName) ?: return false

        // Check if the node or one of its siblings/children is a switch/checkbox
        val checkableNode = findCheckableNodeNear(node)
        if (checkableNode != null) {
            val currentState = checkableNode.isChecked
            val targetState = desiredState ?: !currentState

            if (currentState != targetState) {
                val success = performClickOnNode(checkableNode)
                if (success) {
                    ActionHistoryManager.getInstance().recordAction(
                        description = "Toggled setting '$targetName'",
                        actionType = ActionType.TOGGLE_SWITCH,
                        packageName = root.packageName?.toString() ?: "",
                        elementIdentifier = targetName,
                        previousState = if (currentState) "ON" else "OFF",
                        newState = if (targetState) "ON" else "OFF"
                    )
                    Log.d(TAG, "Toggled '$targetName' from $currentState to $targetState")
                    return true
                }
            } else {
                Log.d(TAG, "'$targetName' is already $targetState")
                return true
            }
        }
        return false
    }

    // Revert the last executed setting or action
    fun revertLastAction(): String {
        val last = ActionHistoryManager.getInstance().popLastAction()
            ?: return "Revert karne ke liye koi pichli setting nahi mili, Sir."

        val root = rootInActiveWindow
        when (last.actionType) {
            ActionType.TOGGLE_SWITCH -> {
                val shouldBeOn = last.previousState.equals("ON", ignoreCase = true)
                val success = toggleSettingSwitch(last.elementIdentifier, shouldBeOn)
                return if (success) {
                    "${last.elementIdentifier} ko wapas ${last.previousState} kar diya gaya hai, Sir."
                } else {
                    "${last.elementIdentifier} ko revert karne me samasya aayi. Kripya screen par option open rakhein."
                }
            }
            ActionType.CLICK_ELEMENT -> {
                // If it was a click, going back typically undoes the screen navigation
                goBack()
                return "Pichla click undo kar diya gaya hai, Sir."
            }
            else -> {
                return "${last.description} ko revert nahi kiya ja sakta, Sir."
            }
        }
    }

    // Text typing into editable field
    fun typeIntoField(targetHintOrText: String?, textToType: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val targetNode: AccessibilityNodeInfo? = if (!targetHintOrText.isNullOrBlank()) {
            findMatchingNode(root, targetHintOrText)
        } else {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        }

        if (targetNode != null) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
            }
            return targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
        return false
    }

    // Scroll Down / Up
    fun scrollDown(): Boolean {
        val root = rootInActiveWindow ?: return false
        return root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    fun scrollUp(): Boolean {
        val root = rootInActiveWindow ?: return false
        return root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    private fun findMatchingNode(root: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        val directMatches = root.findAccessibilityNodeInfosByText(query)
        if (!directMatches.isNullOrEmpty()) {
            return directMatches[0]
        }

        // Fuzzy match
        val list = mutableListOf<AccessibilityNodeInfo>()
        collectAllNodes(root, list)
        return list.firstOrNull { node ->
            val t = node.text?.toString() ?: ""
            val d = node.contentDescription?.toString() ?: ""
            t.contains(query, ignoreCase = true) || d.contains(query, ignoreCase = true)
        }
    }

    private fun findCheckableNodeNear(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isCheckable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null && child.isCheckable) return child
        }
        val parent = node.parent
        if (parent != null) {
            for (i in 0 until parent.childCount) {
                val sibling = parent.getChild(i)
                if (sibling != null && sibling.isCheckable) return sibling
            }
        }
        return null
    }

    private fun collectAllNodes(node: AccessibilityNodeInfo?, list: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        list.add(node)
        for (i in 0 until node.childCount) {
            collectAllNodes(node.getChild(i), list)
        }
    }

    private fun performClickOnNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
        }
        return false
    }
}
