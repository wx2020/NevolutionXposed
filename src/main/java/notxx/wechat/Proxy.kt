package notxx.wechat

import android.app.Notification
import android.app.Notification.Action
import android.app.Notification.CarExtender.UnreadConversation
import android.app.Notification.EXTRA_REMOTE_INPUT_HISTORY
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES
import android.util.Log

import com.oasisfeng.nevo.xposed.BuildConfig

private const val TAG = "WeChat.Proxy"

typealias Cancel = (id: Int) -> Unit
typealias RecastAction = (n: Notification) -> Unit
typealias Recast = (id: Int, action: RecastAction) -> Unit

class Proxy {
	val applicationContext: Context
	val cancel: Cancel
	val recast: Recast
	val readReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context, intent: Intent) = onRead(intent)
	}
	val replyReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context, intent: Intent) = onReply(intent)
	}
	// val zoomReceiver = object : BroadcastReceiver() {
	// 	override fun onReceive(context: Context, intent: Intent) = onZoom(context, intent)
	// }

	constructor(context: Context, cancel: Cancel, recast: Recast) {
		// Log.d(TAG, "Proxy($context)")
		this.applicationContext = context
		this.cancel = cancel
		this.recast = recast
		context.registerReceiver(readReceiver, IntentFilter(ACTION_READ).apply {
			this.addDataScheme(SCHEME_ID)
		})
		context.registerReceiver(replyReceiver, IntentFilter(ACTION_REPLY).apply {
			this.addDataScheme(SCHEME_ID)
		})
		// context.registerReceiver(zoomReceiver, IntentFilter(ACTION_ZOOM).apply {
		// 	this.addDataScheme(SCHEME_ID)
		// })
	}

	fun process(id:Int, n: Notification, c: UnreadConversation, actions: MutableList<Action>) {
		val read = c.readPendingIntent
		if (read == null) return
		// n.deleteIntent = read
		actions.add(buildReadAction(id, read))
		val remoteInput = c.remoteInput
		if (SDK_INT >= VERSION_CODES.N && remoteInput != null) {
			actions.add(buildReplyAction(id, n, c))
		}
	}

	private fun packageContext() = packageContext(BuildConfig.APPLICATION_ID)

	private fun packageContext(packageName: String): Context? {
		return try {
			applicationContext.createPackageContext(packageName, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
		} catch (ig: PackageManager.NameNotFoundException) { null }
	}

	private fun onRead(replyIntent: Intent) {
		// Log.d(TAG, "onRead")
		val readAction = replyIntent.getParcelableExtra<PendingIntent>(EXTRA_READ_ACTION)
		val data = replyIntent.getData()
		if (data == null || readAction == null) return; // Should never happen
		val part = data.getSchemeSpecificPart()
		try {
			// Log.d(TAG, "readAction: $readAction")
			val inputData = readAction.addTargetPackageAndWakeUp();

			readAction.send(applicationContext, 0, inputData, { _, intent, _, _, _ ->
				// Log.d(TAG, "Read sent: ${intent}");
				if (SDK_INT >= VERSION_CODES.N) {
					val id = Integer.parseInt(part);
					cancel(id)
					// markRead(id) TODO
				}
			}, null);
		} catch (e: PendingIntent.CanceledException) {
			Log.w(TAG, "Reply action is already cancelled: ${part}")
			// abortBroadcast()
		}
	}

	private fun onReply(replyIntent: Intent) {
		// Log.d(TAG, "onReply")
		val replyAction = replyIntent.getParcelableExtra<PendingIntent>(EXTRA_REPLY_ACTION)
		val resultKey = replyIntent.getStringExtra(EXTRA_RESULT_KEY)
		val data = replyIntent.getData(); val results = RemoteInput.getResultsFromIntent(replyIntent)
		val input = results?.getCharSequence(resultKey)
		if (data == null || replyAction == null || resultKey == null || input == null) return; // Should never happen
		val inputHistory = if (SDK_INT >= VERSION_CODES.N) { replyIntent.getCharSequenceArrayListExtra(EXTRA_REMOTE_INPUT_HISTORY) } else { null }
		val part = data.getSchemeSpecificPart()
		try {
			// Log.d(TAG, "replyAction: $replyAction")
			val inputData = replyAction.addTargetPackageAndWakeUp();
			inputData.setClipData(replyIntent.clipData);

			replyAction.send(applicationContext, 0, inputData, { _, intent, _, _, _ ->
				// Log.d(TAG, "Reply sent: ${intent}");
				if (SDK_INT >= VERSION_CODES.N) {
					val inputs = if (inputHistory != null) {
						inputHistory.add(input);
						inputHistory.toTypedArray()
					} else { arrayOf(input) }
					val id = Integer.parseInt(part);
					recast(id, { n -> n.remoteInputHistory = inputs })
					// markRead(id) TODO
				}
			}, null);
		} catch (e: PendingIntent.CanceledException) {
			Log.w(TAG, "Reply action is already cancelled: ${part}")
			// abortBroadcast()
		}
	}

	private fun buildReadAction(id: Int, readPendingIntent: PendingIntent): Notification.Action {
		val read = Intent(ACTION_READ)
			.putExtra(EXTRA_READ_ACTION, readPendingIntent)
			.setData(Uri.fromParts(SCHEME_ID, Integer.toString(id), null))
			.setPackage(applicationContext.packageName)
		val proxy = PendingIntent.getBroadcast(applicationContext, 0, read, FLAG_UPDATE_CURRENT)

		return Action.Builder(null, "已读", proxy).build() // TODO
	}

	private fun buildReplyAction(id: Int, n: Notification, conversation: UnreadConversation): Notification.Action {
		val remoteInput = conversation.remoteInput
		val reply = Intent(ACTION_REPLY)
			.putExtra(EXTRA_REPLY_ACTION, conversation.replyPendingIntent)
			.putExtra(EXTRA_RESULT_KEY, remoteInput.resultKey)
			.setData(Uri.fromParts(SCHEME_ID, Integer.toString(id), null))
			.setPackage(applicationContext.packageName)
		val inputHistory = n.remoteInputHistory
		if (SDK_INT >= VERSION_CODES.N && inputHistory != null) {
			reply.putCharSequenceArrayListExtra(EXTRA_REMOTE_INPUT_HISTORY, inputHistory.toCollection(ArrayList()))
		}
		val proxy = PendingIntent.getBroadcast(applicationContext, 0, reply, FLAG_UPDATE_CURRENT)
		val proxyInput = RemoteInput.Builder(remoteInput.resultKey)
			.addExtras(remoteInput.extras).setAllowFreeFormInput(true)
		if (conversation.participant != null) proxyInput.setLabel(conversation.participant)

		val action = Action.Builder(null, "回复", proxy).addRemoteInput(proxyInput.build()) // TODO
		if (SDK_INT >= VERSION_CODES.N) {
			action.setAllowGeneratedReplies(true)
			if (SDK_INT >= VERSION_CODES.P) action.setSemanticAction(Action.SEMANTIC_ACTION_REPLY)
		}
		return action.build()
	}
}

fun PendingIntent.addTargetPackageAndWakeUp() = Intent().addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES).setPackage(this.getCreatorPackage())