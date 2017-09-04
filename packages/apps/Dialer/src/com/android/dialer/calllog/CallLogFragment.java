/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.dialer.calllog;

import static android.Manifest.permission.READ_CALL_LOG;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.DialogFragment;
import android.app.Fragment;
import android.app.KeyguardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.CallLog;
import android.provider.CallLog.Calls;
import android.provider.ContactsContract;
import android.provider.VoicemailContract.Status;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.LinearLayoutManager;

import android.telecom.PhoneAccountHandle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.View.OnClickListener;
import android.view.ViewGroup.LayoutParams;
import android.widget.ListView;
import android.widget.TextView;

import com.android.contacts.common.GeoUtil;
import com.android.contacts.common.util.PermissionsUtil;
import com.android.contacts.common.util.ViewUtil;
import com.android.dialer.R;
import com.android.dialer.list.ListsFragment.HostInterface;
import com.android.dialer.util.DialerUtils;
import com.android.dialer.util.EmptyLoader;
import com.android.dialer.voicemail.VoicemailPlaybackPresenter;
import com.android.dialer.voicemail.VoicemailStatusHelper;
import com.android.dialer.voicemail.VoicemailStatusHelper.StatusMessage;
import com.android.dialer.voicemail.VoicemailStatusHelperImpl;
import com.android.dialer.widget.EmptyContentView;
import com.android.dialer.widget.EmptyContentView.OnEmptyViewActionButtonClickedListener;
import com.android.dialerbind.ObjectFactory;

import com.mediatek.contacts.util.VvmUtils;
import com.mediatek.dialer.activities.CallLogSearchResultActivity;
import com.mediatek.dialer.calllog.PhoneAccountInfoHelper;
import com.mediatek.dialer.calllog.PhoneAccountInfoHelper.AccountInfoListener;
import com.mediatek.dialer.ext.ExtensionManager;
import com.mediatek.dialer.util.DialerConstants;
import com.mediatek.dialer.util.DialerFeatureOptions;

import java.util.List;
//yuan tong qin add start
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemLongClickListener;
import com.android.dialer.care_os.OptionDialog;
import android.view.View;
import android.widget.Button;
import android.view.View.OnClickListener;
import android.app.Dialog;
import android.widget.Toast;
import android.view.Window;
import android.view.WindowManager;
import com.android.dialer.care_os.I99Utils;
import android.view.Gravity;
import com.android.dialer.care_os.I99Font;

// yuan tong qin add end

/**
 * Displays a list of call log entries. To filter for a particular kind of call
 * (all, missed or voicemails), specify it in the constructor.
 */
public class CallLogFragment extends Fragment
        implements CallLogQueryHandler.Listener, CallLogAdapter.CallFetcher,
        OnEmptyViewActionButtonClickedListener, /*M:*/AccountInfoListener {
    private static final String TAG = "CallLogFragment";

    /**
     * ID of the empty loader to defer other fragments.
     */
    private static final int EMPTY_LOADER_ID = 0;

    private static final String KEY_FILTER_TYPE = "filter_type";
    private static final String KEY_LOG_LIMIT = "log_limit";
    private static final String KEY_DATE_LIMIT = "date_limit";
    // No limit specified for the number of logs to show; use the CallLogQueryHandler's default.
    private static final int NO_LOG_LIMIT = -1;
    // No date-based filtering.
    private static final int NO_DATE_LIMIT = 0;

    private static final int READ_CALL_LOG_PERMISSION_REQUEST_CODE = 1;

    private RecyclerView mRecyclerView;
    private LinearLayoutManager mLayoutManager;
    private CallLogAdapter mAdapter;
    private CallLogQueryHandler mCallLogQueryHandler;
    private VoicemailPlaybackPresenter mVoicemailPlaybackPresenter;
    private boolean mScrollToTop;

    /** Whether there is at least one voicemail source installed. */
    private boolean mVoicemailSourcesAvailable = false;

    private EmptyContentView mEmptyListView;
    private KeyguardManager mKeyguardManager;

    
    //yuan tong qin add start 
    private Context mContext;
    //yuan tong qin add end 

    private boolean mEmptyLoaderRunning;
    private boolean mCallLogFetched;
    private boolean mVoicemailStatusFetched;

    private final Handler mHandler = new Handler();

    private class CustomContentObserver extends ContentObserver {
        public CustomContentObserver() {
            super(mHandler);
        }
        @Override
        public void onChange(boolean selfChange) {
            mRefreshDataRequired = true;
        }
    }

    // See issue 6363009
    private final ContentObserver mCallLogObserver = new CustomContentObserver();
    private final ContentObserver mContactsObserver = new CustomContentObserver();
    private final ContentObserver mVoicemailStatusObserver = new CustomContentObserver();
    private boolean mRefreshDataRequired = true;

    private boolean mHasReadCallLogPermission = false;

    // Exactly same variable is in Fragment as a package private.
    private boolean mMenuVisible = true;

    // Default to all calls.
    private int mCallTypeFilter = CallLogQueryHandler.CALL_TYPE_ALL;

    // Log limit - if no limit is specified, then the default in {@link CallLogQueryHandler}
    // will be used.
    private int mLogLimit = NO_LOG_LIMIT;

    // Date limit (in millis since epoch) - when non-zero, only calls which occurred on or after
    // the date filter are included.  If zero, no date-based filtering occurs.
    private long mDateLimit = NO_DATE_LIMIT;

    /// M: [Call Log Account Filter] @{
    private TextView mNoticeText;
    private View mNoticeTextDivider;
    /// @}


    /*
     * True if this instance of the CallLogFragment is the Recents screen shown in
     * DialtactsActivity.
     */
    private boolean mIsRecentsFragment;

    public interface HostInterface {
        public void showDialpad();
    }

    public CallLogFragment() {
        this(CallLogQueryHandler.CALL_TYPE_ALL, NO_LOG_LIMIT);
    }

    public CallLogFragment(int filterType) {
        this(filterType, NO_LOG_LIMIT);
    }

    public CallLogFragment(int filterType, int logLimit) {
        this(filterType, logLimit, NO_DATE_LIMIT);
    }

    /**
     * Creates a call log fragment, filtering to include only calls of the desired type, occurring
     * after the specified date.
     * @param filterType type of calls to include.
     * @param dateLimit limits results to calls occurring on or after the specified date.
     */
    public CallLogFragment(int filterType, long dateLimit) {
        this(filterType, NO_LOG_LIMIT, dateLimit);
    }

    public CallLogFragment(int filterType, int logLimit, long dateLimit) {
        mCallTypeFilter = filterType;
        mLogLimit = logLimit;
        mDateLimit = dateLimit;
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            mCallTypeFilter = state.getInt(KEY_FILTER_TYPE, mCallTypeFilter);
            mLogLimit = state.getInt(KEY_LOG_LIMIT, mLogLimit);
            mDateLimit = state.getLong(KEY_DATE_LIMIT, mDateLimit);
            /// M: [Call Log Account Filter]
            mNeedAccountFilter = state.getBoolean(KEY_NEED_ACCOUNT_FILTER);
        }

        mIsRecentsFragment = mLogLimit != NO_LOG_LIMIT;

        final Activity activity = getActivity();
        final ContentResolver resolver = activity.getContentResolver();
        String currentCountryIso = GeoUtil.getCurrentCountryIso(activity);
        mCallLogQueryHandler = new CallLogQueryHandler(activity, resolver, this, mLogLimit);
        mKeyguardManager =
                (KeyguardManager) activity.getSystemService(Context.KEYGUARD_SERVICE);
        resolver.registerContentObserver(CallLog.CONTENT_URI, true, mCallLogObserver);
        resolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true,
                mContactsObserver);
        resolver.registerContentObserver(Status.CONTENT_URI, true, mVoicemailStatusObserver);
        setHasOptionsMenu(true);

        if (mCallTypeFilter == Calls.VOICEMAIL_TYPE) {
            mVoicemailPlaybackPresenter = VoicemailPlaybackPresenter
                    .getInstance(activity, state);
        }

        /// M: [Call Log Account Filter] add account change listener
        if (DialerFeatureOptions.isCallLogAccountFilterEnabled()) {
            PhoneAccountInfoHelper.getInstance(getActivity()).registerForAccountChange(this);
        }
        /// @}
		// yuan tong qin add start         
        mContext = this.getActivity();
    }

    /** Called by the CallLogQueryHandler when the list of calls has been fetched or updated. */
    @Override
    public boolean onCallsFetched(Cursor cursor) {
        if (getActivity() == null || getActivity().isFinishing()) {
            // Return false; we did not take ownership of the cursor
            return false;
        }
        mAdapter.setLoading(false);
        mAdapter.changeCursor(cursor);
        // This will update the state of the "Clear call log" menu item.
        getActivity().invalidateOptionsMenu();

        boolean showListView = cursor != null && cursor.getCount() > 0;
        mRecyclerView.setVisibility(showListView ? View.VISIBLE : View.GONE);
        mEmptyListView.setVisibility(!showListView ? View.VISIBLE : View.GONE);

        if (mScrollToTop) {
            // The smooth-scroll animation happens over a fixed time period.
            // As a result, if it scrolls through a large portion of the list,
            // each frame will jump so far from the previous one that the user
            // will not experience the illusion of downward motion.  Instead,
            // if we're not already near the top of the list, we instantly jump
            // near the top, and animate from there.
            if (mLayoutManager.findFirstVisibleItemPosition() > 5) {
                // TODO: Jump to near the top, then begin smooth scroll.
                mRecyclerView.smoothScrollToPosition(0);
            }
            // Workaround for framework issue: the smooth-scroll doesn't
            // occur if setSelection() is called immediately before.
            mHandler.post(new Runnable() {
               @Override
               public void run() {
                   if (getActivity() == null || getActivity().isFinishing()) {
                       return;
                   }
                   mRecyclerView.smoothScrollToPosition(0);
               }
            });

            mScrollToTop = false;
        }
        mCallLogFetched = true;
        destroyEmptyLoaderIfAllDataFetched();

        /** M:  [Dialer Global Search] notify search activity update search result. @{*/
        updateSearchResultIfNeed(cursor);
        /** @}*/
        return true;
    }

    /**
     * Called by {@link CallLogQueryHandler} after a successful query to voicemail status provider.
     */
    @Override
    public void onVoicemailStatusFetched(Cursor statusCursor) {
        Activity activity = getActivity();
        if (activity == null || activity.isFinishing()) {
            return;
        }

        mVoicemailStatusFetched = true;
        destroyEmptyLoaderIfAllDataFetched();
    }

    private void destroyEmptyLoaderIfAllDataFetched() {
        if (mCallLogFetched && mVoicemailStatusFetched && mEmptyLoaderRunning) {
            mEmptyLoaderRunning = false;
            getLoaderManager().destroyLoader(EMPTY_LOADER_ID);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedState) {
        View view = inflater.inflate(R.layout.call_log_fragment, container, false);

        /** M: [Call Log Account Filter] add Notice for account filter @{ */
        mNoticeText = (TextView) view.findViewById(R.id.notice_text);
        mNoticeTextDivider = view.findViewById(R.id.notice_text_divider);
        /** @} */

        mRecyclerView = (RecyclerView) view.findViewById(R.id.recycler_view);
        mRecyclerView.setHasFixedSize(true);
        mLayoutManager = new LinearLayoutManager(getActivity());
        mRecyclerView.setLayoutManager(mLayoutManager);
        mEmptyListView = (EmptyContentView) view.findViewById(R.id.empty_list_view);
        mEmptyListView.setImage(R.drawable.empty_call_log);
        mEmptyListView.setActionClickedListener(this);

        String currentCountryIso = GeoUtil.getCurrentCountryIso(getActivity());
        boolean isShowingRecentsTab = mLogLimit != NO_LOG_LIMIT || mDateLimit != NO_DATE_LIMIT;
        mAdapter = ObjectFactory.newCallLogAdapter(
                getActivity(),
                this,
                new ContactInfoHelper(getActivity(), currentCountryIso),
                mVoicemailPlaybackPresenter,
                isShowingRecentsTab);
        mRecyclerView.setAdapter(mAdapter);

        fetchCalls();
        return view;
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        updateEmptyMessage(mCallTypeFilter);
        mAdapter.onRestoreInstanceState(savedInstanceState);
    }

    @Override
    public void onStart() {
        // Start the empty loader now to defer other fragments.  We destroy it when both calllog
        // and the voicemail status are fetched.
        getLoaderManager().initLoader(EMPTY_LOADER_ID, null,
                new EmptyLoader.Callback(getActivity()));
        mEmptyLoaderRunning = true;
        super.onStart();
    }

    @Override
    public void onResume() {
        super.onResume();
        final boolean hasReadCallLogPermission =
                PermissionsUtil.hasPermission(getActivity(), READ_CALL_LOG);
        if (!mHasReadCallLogPermission && hasReadCallLogPermission) {
            // We didn't have the permission before, and now we do. Force a refresh of the call log.
            // Note that this code path always happens on a fresh start, but mRefreshDataRequired
            // is already true in that case anyway.
            mRefreshDataRequired = true;
            updateEmptyMessage(mCallTypeFilter);
        }
        mHasReadCallLogPermission = hasReadCallLogPermission;
        refreshData();
        mAdapter.startCache();
    }

    @Override
    public void onPause() {
        if (mVoicemailPlaybackPresenter != null) {
            mVoicemailPlaybackPresenter.onPause();
        }
        mAdapter.pauseCache();
        super.onPause();
    }

    @Override
    public void onStop() {
        updateOnTransition(false /* onEntry */);

        super.onStop();
    }

    @Override
    public void onDestroy() {
        mAdapter.pauseCache();
        mAdapter.changeCursor(null);

        if (mVoicemailPlaybackPresenter != null) {
            mVoicemailPlaybackPresenter.onDestroy();
        }

        getActivity().getContentResolver().unregisterContentObserver(mCallLogObserver);
        getActivity().getContentResolver().unregisterContentObserver(mContactsObserver);
        getActivity().getContentResolver().unregisterContentObserver(mVoicemailStatusObserver);

        /// M: [Call Log Account Filter] unregister account change listener
        if (DialerFeatureOptions.isCallLogAccountFilterEnabled()) {
            PhoneAccountInfoHelper.getInstance(getActivity()).unRegisterForAccountChange(this);
        }
        super.onDestroy();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(KEY_FILTER_TYPE, mCallTypeFilter);
        outState.putInt(KEY_LOG_LIMIT, mLogLimit);
        outState.putLong(KEY_DATE_LIMIT, mDateLimit);

        /// M: [Call Log Account Filter]
        outState.putBoolean(KEY_NEED_ACCOUNT_FILTER, mNeedAccountFilter);

        mAdapter.onSaveInstanceState(outState);

        if (mVoicemailPlaybackPresenter != null) {
            mVoicemailPlaybackPresenter.onSaveInstanceState(outState);
        }
    }

    @Override
    public void fetchCalls() {
        /** M: [Dialer Global Search] Displays a list of call log entries @{ */
        if (isQueryMode()) {
            startSearchCalls(mQueryData);
        } else {
            /// M: [Call Log Account Filter] add call log account filter support
            mCallLogQueryHandler.fetchCalls(mCallTypeFilter, mDateLimit, getAccountFilterId());
        }
        /** @} */
    }

    private void updateEmptyMessage(int filterType) {
        final Context context = getActivity();
        if (context == null) {
            return;
        }

        if (!PermissionsUtil.hasPermission(context, READ_CALL_LOG)) {
            mEmptyListView.setDescription(R.string.permission_no_calllog);
            mEmptyListView.setActionLabel(R.string.permission_single_turn_on);
            return;
        }

        final int messageId;
        switch (filterType) {
            case Calls.MISSED_TYPE:
                messageId = R.string.recentMissed_empty;
                break;
            case Calls.VOICEMAIL_TYPE:
                messageId = R.string.recentVoicemails_empty;
                break;
            case CallLogQueryHandler.CALL_TYPE_ALL:
                /** M: [Dialer Global Search] Search mode with customer empty string. */
                messageId = isQueryMode() ? R.string.noMatchingCalllogs
                            : R.string.recentCalls_empty;
                /** @} */
                break;
            /** M: [CallLog Incoming and Outgoing Filter] @{ */
            case Calls.INCOMING_TYPE:
                messageId = R.string.recentCalls_empty;
                break;
            case Calls.OUTGOING_TYPE:
                messageId = R.string.recentCalls_empty;
                break;
            /** @} */
            default:
                throw new IllegalArgumentException("Unexpected filter type in CallLogFragment: "
                        + filterType);
        }
        mEmptyListView.setDescription(messageId);
        if (mIsRecentsFragment) {
            mEmptyListView.setActionLabel(R.string.recentCalls_empty_action);
        } else {
            mEmptyListView.setActionLabel(EmptyContentView.NO_LABEL);
        }
    }

    CallLogAdapter getAdapter() {
        return mAdapter;
    }

    @Override
    public void onEmptyViewActionButtonClicked() {
        final Activity activity = getActivity();
        if (activity == null) {
            return;
        }

        if (!PermissionsUtil.hasPermission(activity, READ_CALL_LOG)) {
            requestPermissions(new String[] {READ_CALL_LOG}, READ_CALL_LOG_PERMISSION_REQUEST_CODE);
        } else if (mIsRecentsFragment) {
            // Show dialpad if we are the recents fragment.
            ((HostInterface) activity).showDialpad();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        if (requestCode == READ_CALL_LOG_PERMISSION_REQUEST_CODE) {
            if (grantResults.length >= 1 && PackageManager.PERMISSION_GRANTED == grantResults[0]) {
                // Force a refresh of the data since we were missing the permission before this.
                mRefreshDataRequired = true;
            }
        }
    }

    @Override
    public void setMenuVisibility(boolean menuVisible) {
        super.setMenuVisibility(menuVisible);
        if (mMenuVisible != menuVisible) {
            mMenuVisible = menuVisible;
            if (!menuVisible) {
                updateOnTransition(false /* onEntry */);
            } else if (isResumed()) {
                refreshData();
            }
        }
    }

    /** Requests updates to the data to be shown. */
    private void refreshData() {
        // Prevent unnecessary refresh.
        if (mRefreshDataRequired) {
            // Mark all entries in the contact info cache as out of date, so they will be looked up
            // again once being shown.
            mAdapter.invalidateCache();
            mAdapter.setLoading(true);

            fetchCalls();
            mCallLogQueryHandler.fetchVoicemailStatus();

            updateOnTransition(true /* onEntry */);
            mRefreshDataRequired = false;
        } else {
            // Refresh the display of the existing data to update the timestamp text descriptions.
            mAdapter.notifyDataSetChanged();
        }

        /** M: [Call Log Account Filter] @{ */
        if (mNeedAccountFilter) {
            updateNotice();
        }
        /** @} */
    }

    /**
     * Updates the call data and notification state on entering or leaving the call log tab.
     *
     * If we are leaving the call log tab, mark all the missed calls as read.
     *
     * TODO: Move to CallLogActivity
     */
    private void updateOnTransition(boolean onEntry) {
        // We don't want to update any call data when keyguard is on because the user has likely not
        // seen the new calls yet.
        // This might be called before onCreate() and thus we need to check null explicitly.
        if (mKeyguardManager != null && !mKeyguardManager.inKeyguardRestrictedInputMode()) {
            // On either of the transitions we update the missed call and voicemail notifications.
            // While exiting we additionally consume all missed calls (by marking them as read).
            mCallLogQueryHandler.markNewCallsAsOld();
            if (!onEntry) {
                mCallLogQueryHandler.markMissedCallsAsRead();
            }
            CallLogNotificationsHelper.removeMissedCallNotifications(getActivity());
            CallLogNotificationsHelper.updateVoicemailNotifications(getActivity());
        }
    }

    /// M: [Multi-Delete] For CallLog delete @{
    @Override
    public void onCallsDeleted() {
        // Do nothing
    }
    /// @}

    /// M: [Call Log Account Filter] @{
    private static final String KEY_NEED_ACCOUNT_FILTER = "need_account_filter";
    // Whether or not to use account filter, currently call log screen use account filter
    // while recents call log  need not
    private boolean mNeedAccountFilter = DialerFeatureOptions.isCallLogAccountFilterEnabled();

    public void setAccountFilterState(boolean enable) {
        mNeedAccountFilter = enable;
    }

    private String getAccountFilterId() {
        if (DialerFeatureOptions.isCallLogAccountFilterEnabled() && mNeedAccountFilter) {
            return PhoneAccountInfoHelper.getInstance(getActivity()).getPreferAccountId();
        } else {
            return PhoneAccountInfoHelper.FILTER_ALL_ACCOUNT_ID;
        }
    }

    private void updateNotice() {
        String lable = null;
        String id = PhoneAccountInfoHelper.getInstance(getActivity()).getPreferAccountId();
        if (getActivity() != null && !PhoneAccountInfoHelper.FILTER_ALL_ACCOUNT_ID.equals(id)) {
            PhoneAccountHandle account = PhoneAccountUtils.getPhoneAccountById(getActivity(), id);
            if (account != null) {
                lable = PhoneAccountUtils.getAccountLabel(getActivity(), account);
            }
        }
        if (!TextUtils.isEmpty(lable) && mNoticeText != null && mNoticeTextDivider != null) {
            mNoticeText.setText(getActivity().getString(R.string.call_log_via_sim_name_notice,
                    lable));
            mNoticeText.setVisibility(View.VISIBLE);
            mNoticeTextDivider.setVisibility(View.VISIBLE);
        } else {
            mNoticeText.setVisibility(View.GONE);
            mNoticeTextDivider.setVisibility(View.GONE);
        }
    }

    @Override
    public void onAccountInfoUpdate() {
        // clear account cache, and refresh list items
        mAdapter.pauseCache();
        forceToRefreshData();
    }

    @Override
    public void onPreferAccountChanged(String id) {
        forceToRefreshData();
    }
    /// @}

    public void forceToRefreshData() {
        mRefreshDataRequired = true;
        /// M: for ALPS01683374
        // refreshData only when CallLogFragment is in foreground
        if (isResumed()) {
            refreshData();
        }
    }

    /**
     * M: [Dialer Global Search] Displays a list of call log entries.
     * CallLogSearch activity reused CallLogFragment.  @{
     */
    // Default null, while in search mode it is not null.
    private String mQueryData = null;

    /**
     * Use it to inject search data.
     * This is the entrance of call log search mode.
     * @param query
     */
    public void setQueryData(String query) {
        mQueryData = query;
        mAdapter.setQueryString(query);
    }

    private void startSearchCalls(String query) {
        Uri uri = Uri.withAppendedPath(DialerConstants.CALLLOG_SEARCH_URI_BASE, query);
        /// support search Voicemail calllog
        uri = VvmUtils.buildVvmAllowedUri(uri);

        mCallLogQueryHandler.fetchSearchCalls(uri);
    }

    private boolean isQueryMode() {
        return !TextUtils.isEmpty(mQueryData) && DialerFeatureOptions.DIALER_GLOBAL_SEARCH;
    }

    private void updateSearchResultIfNeed(Cursor result) {
        if (isQueryMode() && getActivity() instanceof CallLogSearchResultActivity) {
            int count = result != null ? result.getCount() : 0;
            ((CallLogSearchResultActivity) getActivity()).updateSearchResult(count);
        }
    }

    public Cursor getCursor() {
        return mAdapter.getCursor();
    }
    /** @} */
    
    //yuan tong qin add start
   
			 
			
    private final   AdapterView.OnItemLongClickListener onitemlong=new AdapterView.OnItemLongClickListener() {

		@Override
		public boolean onItemLongClick(AdapterView<?> arg0, View view,
				int arg2, long arg3) {
			
//			  CallLogListItemViews item = (CallLogListItemViews)view;
//		      final String title = item.getCallLogNameTextView().getText().toString();
//	            IntentProvider intentProvider = (IntentProvider) view.getTag();
//	            final Intent intent = intentProvider.getIntent(mContext);
//			showDeleteItemDialog(intent,title);
//	            showDeleteItemDialog(null,"2222");
	            Toast.makeText(mContext, "====?????==", 0).show();
			return true;
		}
	};
    
    private void showDeleteItemDialog(final Intent intent,String name){

        final OptionDialog dialog = new OptionDialog(mContext);
        dialog.setTitle(name);
        dialog.addButton(R.string.i99_call_log_delete_group);
        dialog.setOnClickListener(new OptionDialog.OnClickListener(){
            @Override
            public void onClick(View v){
                switch(v.getId()){
                    case R.string.i99_call_log_delete_group:
                    showDeleteConfirmDialog(intent);
                    break;
                }
            }
        });
        dialog.show();
    }

    private void showDeleteConfirmDialog(final Intent intent){
        final Dialog dialog = new Dialog(mContext, R.style.I99DialogStyle);
        LayoutInflater inflater = LayoutInflater.from(mContext);
        View view = inflater.inflate(R.layout.i99_call_log_delete_confirm, null);
        TextView title = (TextView)view.findViewById(R.id.title);
        TextView content = (TextView)view.findViewById(R.id.content);
        Button ok = (Button)view.findViewById(R.id.ok);
        ok.setOnClickListener(new View.OnClickListener(){
            @Override
            public void onClick(View v){
                onRemoveFromCallLog(intent);
                dialog.dismiss();
            }
        });
        Button cancel = (Button)view.findViewById(R.id.cancel);
        cancel.setOnClickListener(new View.OnClickListener(){
            @Override
            public void onClick(View v){
                dialog.dismiss();
            }

        });
        ok.setTextSize(I99Font.TITLE);
        cancel.setTextSize(I99Font.TITLE);
        title.setTextSize(I99Font.TITLE);
        content.setTextSize(I99Font.TITLE);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT ,WindowManager.LayoutParams.WRAP_CONTENT);
        lp.width = I99Utils.getScreenSize(mContext)[0];
        dialog.addContentView(view, lp);
        dialog.show();
        Window window = dialog.getWindow();
        lp = window.getAttributes();
        lp.gravity = Gravity.BOTTOM;
        window.setAttributes(lp);
        window.setWindowAnimations(R.style.I99DialogAnim);
    }
    
    public void onRemoveFromCallLog(Intent intent) {
        final StringBuilder callIds = new StringBuilder();

//        for (Uri callUri : getCallLogEntryUris(intent)) {
//            if (callIds.length() != 0) {
//                callIds.append(",");
//            }
//            callIds.append(ContentUris.parseId(callUri));
//        }
//   //     ????????
//        mAsyncTaskExecutor.submit(Tasks.REMOVE_FROM_CALL_LOG_AND_FINISH,
//                new AsyncTask<Void, Void, Void>() {
//                    @Override
//                    public Void doInBackground(Void... params) {
//                        mContext.getContentResolver().delete(Calls.CONTENT_URI_WITH_VOICEMAIL,
//                                Calls._ID + " IN (" + callIds + ")", null);
//                        return null;
//                    }
//
//                    @Override
//                    public void onPostExecute(Void result) {
//
//                    }
//                });
    }
    
    // yuan tong qin add end
}