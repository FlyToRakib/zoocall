package app.zoocall.core.store

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.platform.mac.CoreFoundation
import com.sun.jna.platform.mac.CoreFoundation.CFDataRef
import com.sun.jna.platform.mac.CoreFoundation.CFDictionaryRef
import com.sun.jna.platform.mac.CoreFoundation.CFIndex
import com.sun.jna.platform.mac.CoreFoundation.CFMutableDictionaryRef
import com.sun.jna.platform.mac.CoreFoundation.CFStringRef
import com.sun.jna.platform.mac.CoreFoundation.CFTypeRef
import com.sun.jna.ptr.PointerByReference

class KeychainException(val status: Int) : Exception("Keychain error $status")

/**
 * Secrets in the macOS login Keychain as generic-password items, through Security.framework
 * (`SecItem*`). [service] groups Zoocall's items; each secret is one account.
 */
internal class MacKeychain(private val service: String) {
    @Suppress("FunctionName")
    private interface SecurityFramework : Library {
        fun SecItemAdd(attributes: CFDictionaryRef, result: Pointer?): Int
        fun SecItemCopyMatching(query: CFDictionaryRef, result: PointerByReference): Int
        fun SecItemDelete(query: CFDictionaryRef): Int
    }

    private val security: SecurityFramework = Native.load("Security", SecurityFramework::class.java)
    private val securityLibrary = NativeLibrary.getInstance("Security")
    private val coreFoundationLibrary = NativeLibrary.getInstance("CoreFoundation")
    private val cf = CoreFoundation.INSTANCE

    private fun securityString(name: String) = CFStringRef(securityLibrary.getGlobalVariableAddress(name).getPointer(0))

    private val secClass = securityString("kSecClass")
    private val secClassGenericPassword = securityString("kSecClassGenericPassword")
    private val secAttrService = securityString("kSecAttrService")
    private val secAttrAccount = securityString("kSecAttrAccount")
    private val secValueData = securityString("kSecValueData")
    private val secReturnData = securityString("kSecReturnData")
    private val secMatchLimit = securityString("kSecMatchLimit")
    private val secMatchLimitOne = securityString("kSecMatchLimitOne")
    private val booleanTrue = CFTypeRef(coreFoundationLibrary.getGlobalVariableAddress("kCFBooleanTrue").getPointer(0))
    private val keyCallbacks = coreFoundationLibrary.getGlobalVariableAddress("kCFTypeDictionaryKeyCallBacks")
    private val valueCallbacks = coreFoundationLibrary.getGlobalVariableAddress("kCFTypeDictionaryValueCallBacks")

    fun load(account: String): ByteArray? = withItemQuery(account) { query ->
        cf.CFDictionarySetValue(query, secReturnData, booleanTrue)
        cf.CFDictionarySetValue(query, secMatchLimit, secMatchLimitOne)
        val result = PointerByReference()
        when (val status = security.SecItemCopyMatching(query, result)) {
            SUCCESS -> {
                val data = CFDataRef(result.value)
                try {
                    val length = cf.CFDataGetLength(data).toInt()
                    cf.CFDataGetBytePtr(data).getByteArray(0, length)
                } finally {
                    cf.CFRelease(data)
                }
            }
            ITEM_NOT_FOUND -> null
            else -> throw KeychainException(status)
        }
    }

    fun save(account: String, secret: ByteArray) {
        require(secret.isNotEmpty())
        delete(account)
        withItemQuery(account) { query ->
            val memory = Memory(secret.size.toLong()).apply { write(0, secret, 0, secret.size) }
            val data = cf.CFDataCreate(null, memory, CFIndex(secret.size.toLong()))
            try {
                cf.CFDictionarySetValue(query, secValueData, data)
                val status = security.SecItemAdd(query, null)
                if (status != SUCCESS) throw KeychainException(status)
            } finally {
                cf.CFRelease(data)
                memory.clear()
            }
        }
    }

    fun delete(account: String) = withItemQuery(account) { query ->
        val status = security.SecItemDelete(query)
        if (status != SUCCESS && status != ITEM_NOT_FOUND) throw KeychainException(status)
    }

    /** A query for this service's item named [account]; released afterwards. */
    private inline fun <T> withItemQuery(account: String, block: (CFMutableDictionaryRef) -> T): T {
        val query = cf.CFDictionaryCreateMutable(null, CFIndex(0), keyCallbacks, valueCallbacks)
        val serviceRef = CFStringRef.createCFString(service)
        val accountRef = CFStringRef.createCFString(account)
        try {
            cf.CFDictionarySetValue(query, secClass, secClassGenericPassword)
            cf.CFDictionarySetValue(query, secAttrService, serviceRef)
            cf.CFDictionarySetValue(query, secAttrAccount, accountRef)
            return block(query)
        } finally {
            cf.CFRelease(accountRef)
            cf.CFRelease(serviceRef)
            cf.CFRelease(query)
        }
    }

    private companion object {
        const val SUCCESS = 0
        const val ITEM_NOT_FOUND = -25300
    }
}
