package com.sigma.ptr;

import android.content.Context;
import android.content.res.TypedArray;
import android.os.Build;
import android.support.v7.widget.RecyclerView;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.ListView;
import android.widget.Scroller;
import android.widget.TextView;

import com.sigma.ptr.indicator.PtrIndicator;
import com.sigma.ptr.util.PtrCLog;

/**
 * This layout view for "Pull to Refresh(Ptr)" support all of the view, you can contain everything you want.
 * support: pull to refresh / release to refresh / auto refresh / keep header view while refreshing / hide header view while refreshing
 * It defines {@link PtrUIHandler}, which allows you customize the UI easily.
 */
public class PtrFrameLayout extends ViewGroup {

    // status enum
    public final static byte PTR_STATUS_INIT = 1;
    private byte mPtrStatus = PTR_STATUS_INIT;
    public final static byte PTR_STATUS_PREPARE = 2;
    public final static byte PTR_STATUS_LOADING = 3;
    public final static byte PTR_STATUS_COMPLETE = 4;
    public final static byte LOAD_MORE_STATUS_INIT = 5;
    private byte mLoadMoreStatus = LOAD_MORE_STATUS_INIT;
    public final static byte LOAD_MORE_STATUS_LOADING = 6;
    public final static byte LOAD_MORE_STATUS_COMPLETE = 7;
    private static final boolean DEBUG_LAYOUT = true;
    public static boolean DEBUG = false;
    private static int ID = 1;
    protected final String LOG_TAG = "ptr-frame-" + ++ID;
    // auto refresh status
    private final static byte FLAG_AUTO_REFRESH_AT_ONCE = 0x01;
    private final static byte FLAG_AUTO_REFRESH_BUT_LATER = 0x01 << 1;
    private final static byte FLAG_ENABLE_NEXT_PTR_AT_ONCE = 0x01 << 2;
    private final static byte FLAG_PIN_CONTENT = 0x01 << 3;
    private final static byte MASK_AUTO_REFRESH = 0x03;
    private final static byte MASK_AUTO_LOAD_MORE = 0x04;
    protected View mContent;
    // optional config for define header and content in xml file
    private int mHeaderId = 0;
    private int mContainerId = 0;
    // config
    private int mDurationToClose = 200;
    private int mDurationToCloseHeader = 1000;
    private boolean mKeepHeaderWhenRefresh = true;
    private boolean mPullToRefresh = false;
    private View mHeaderView;
    private View mFooterView;
    private PtrUIHandlerHolder mPtrUIHandlerHolder = PtrUIHandlerHolder.create();
    private PtrHandler mPtrHandler;
    private LoadMoreUIHandlerHolder mLoadMoreUIHandlerHolder = LoadMoreUIHandlerHolder.create();
    private LoadMoreHandler mLoadMoreHandler;
    // working parameters
    private ScrollChecker mScrollChecker;
    private int mPagingTouchSlop;
    private int mHeaderHeight;
    private int mFooterHeight;
    private int mContentHeight = -1;
    private boolean mDisableWhenHorizontalMove = false;
    private int mFlag = MASK_AUTO_LOAD_MORE;

    // disable when detect moving horizontally
    private boolean mPreventForHorizontal = false;

    private MotionEvent mLastMoveEvent;

    private PtrUIHandlerHook mRefreshCompleteHook;

    private int mLoadingMinTime = 500;
    private long mLoadingStartTime = 0;
    private PtrIndicator mPtrIndicator;
    private boolean mHasSendCancelEvent = false;
    private Runnable mPerformRefreshCompleteDelay = new Runnable() {
        @Override
        public void run() {
            performRefreshComplete();
        }
    };
    private Runnable mPerformLoadMoreCompleteDelay = new Runnable() {
        @Override
        public void run() {
            performLoadMoreComplete();
        }
    };

    public PtrFrameLayout(Context context) {
        this(context, null);
    }

    public PtrFrameLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PtrFrameLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        mPtrIndicator = new PtrIndicator();

        TypedArray arr = context.obtainStyledAttributes(attrs, R.styleable.PtrFrameLayout, 0, 0);
        if (arr != null) {

            mHeaderId = arr.getResourceId(R.styleable.PtrFrameLayout_ptr_header, mHeaderId);
            mContainerId = arr.getResourceId(R.styleable.PtrFrameLayout_ptr_content, mContainerId);

            mPtrIndicator.setResistance(
                    arr.getFloat(R.styleable.PtrFrameLayout_ptr_resistance, mPtrIndicator.getResistance()));

            mDurationToClose = arr.getInt(R.styleable.PtrFrameLayout_ptr_duration_to_close, mDurationToClose);
            mDurationToCloseHeader = arr.getInt(R.styleable.PtrFrameLayout_ptr_duration_to_close_header, mDurationToCloseHeader);

            float ratio = mPtrIndicator.getRatioOfHeaderToHeightRefresh();
            ratio = arr.getFloat(R.styleable.PtrFrameLayout_ptr_ratio_of_header_height_to_refresh, ratio);
            mPtrIndicator.setRatioOfHeaderHeightToRefresh(ratio);

            mKeepHeaderWhenRefresh = arr.getBoolean(R.styleable.PtrFrameLayout_ptr_keep_header_when_refresh, mKeepHeaderWhenRefresh);

            mPullToRefresh = arr.getBoolean(R.styleable.PtrFrameLayout_ptr_pull_to_fresh, mPullToRefresh);

            boolean isAutoLoadMore = arr.getBoolean(R.styleable.PtrFrameLayout_ptr_auto_load_more, true);
            if (isAutoLoadMore) {
                mFlag = mFlag | MASK_AUTO_LOAD_MORE;
            } else {
                mFlag = mFlag & ~MASK_AUTO_LOAD_MORE;
            }
            arr.recycle();
        }

        mScrollChecker = new ScrollChecker();

        final ViewConfiguration conf = ViewConfiguration.get(getContext());
        mPagingTouchSlop = conf.getScaledTouchSlop() * 2;
    }

    @Override
    protected void onFinishInflate() {
        final int childCount = getChildCount();
        if (childCount > 3) {
            throw new IllegalStateException("PtrFrameLayout can only contains 3 children");
        } else if (childCount == 2) {
            if (mHeaderId != 0 && mHeaderView == null) {
                mHeaderView = findViewById(mHeaderId);
            }
            if (mContainerId != 0 && mContent == null) {
                mContent = findViewById(mContainerId);
            }
            // not specify header or content
            if (mContent == null || mHeaderView == null) {

                View child1 = getChildAt(0);
                View child2 = getChildAt(1);
                if (child1 instanceof PtrUIHandler) {
                    mHeaderView = child1;
                    mContent = child2;
                } else if (child2 instanceof PtrUIHandler) {
                    mHeaderView = child2;
                    mContent = child1;
                } else {
                    // both are not specified
                    if (mContent == null && mHeaderView == null) {
                        mHeaderView = child1;
                        mContent = child2;
                    }
                    // only one is specified
                    else {
                        if (mHeaderView == null) {
                            mHeaderView = mContent == child1 ? child2 : child1;
                        } else {
                            mContent = mHeaderView == child1 ? child2 : child1;
                        }
                    }
                }
            }
        } else if (childCount == 3) {
            if (mHeaderId != 0 && mHeaderView == null) {
                mHeaderView = findViewById(mHeaderId);
            }
            if (mContainerId != 0 && mContent == null) {
                mContent = findViewById(mContainerId);
            }
            int headerIndex = 0;
            if (mHeaderView == null) {
                for (int i = 0; i < childCount; i++) {
                    View child = getChildAt(i);
                    if (child instanceof PtrUIHandler) {
                        mHeaderView = child;
                        headerIndex = i;
                        break;
                    }
                }
            }
            int footerIndex = 0;
            if (mFooterView == null) {
                for (int i = 0; i < childCount; i++) {
                    View child = getChildAt(i);
                    if (child instanceof LoadMoreUIHandler) {
                        mFooterView = child;
                        footerIndex = 0;
                        break;
                    }
                }
            }
            if (mContent == null) {
                for (int i = 0; i < childCount; i++) {
                    if (i == headerIndex || i == footerIndex) {
                        continue;
                    }
                    mContent = getChildAt(i);
                }
            }
        } else if (childCount == 1) {
            mContent = getChildAt(0);
        } else {
            TextView errorView = new TextView(getContext());
            errorView.setClickable(true);
            errorView.setTextColor(0xffff6600);
            errorView.setGravity(Gravity.CENTER);
            errorView.setTextSize(20);
            errorView.setText("The content view in PtrFrameLayout is empty. Do you forget to specify its id in xml layout file?");
            mContent = errorView;
            addView(mContent);
        }
        if (mHeaderView != null) {
            mHeaderView.bringToFront();
        }
        if (mFooterView != null) {
            mFooterView.bringToFront();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            mContent.setOnScrollChangeListener(new OnScrollChangeListener() {
                @Override
                public void onScrollChange(View v, int scrollX, int scrollY, int oldScrollX, int oldScrollY) {
                    if (isAutoLoadMore() && !mContent.canScrollVertically(1)) {
                        tryPerformLoadMore();
                    }
                }
            });
        } else if (mContent instanceof RecyclerView) {
            ((RecyclerView) mContent).addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                    if (isAutoLoadMore() && newState == RecyclerView.SCROLL_STATE_IDLE && !recyclerView.canScrollVertically(1)) {
                        tryPerformLoadMore();
                    }
                }

                @Override
                public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                }
            });
        } else if (mContent instanceof ListView) {
            ((ListView) mContent).setOnScrollListener(new AbsListView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(AbsListView view, int scrollState) {
                    if (scrollState == RecyclerView.SCROLL_STATE_IDLE && !view.canScrollVertically(1)) {
                        tryPerformLoadMore();
                    }
                }

                @Override
                public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {

                }
            });
        }
        super.onFinishInflate();
    }

    private void tryPerformLoadMore() {
        if (mPtrStatus == PTR_STATUS_LOADING) {
            return;
        }
        if (mLoadMoreStatus == LOAD_MORE_STATUS_LOADING) {
            if (mFooterView.getBottom() <= mContentHeight) {
                return;
            }
            int offsetY = mContentHeight - mFooterView.getBottom();
            mContent.offsetTopAndBottom(offsetY);
            mFooterView.offsetTopAndBottom(offsetY);
            return;
        }
        performLoadMore();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mScrollChecker != null) {
            mScrollChecker.destroy();
        }

        if (mPerformRefreshCompleteDelay != null) {
            removeCallbacks(mPerformRefreshCompleteDelay);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        if (isDebug()) {
            PtrCLog.d(LOG_TAG, "onMeasure frame: width: %s, height: %s, padding: %s %s %s %s",
                    getMeasuredHeight(), getMeasuredWidth(),
                    getPaddingLeft(), getPaddingRight(), getPaddingTop(), getPaddingBottom());

        }

        if (mHeaderView != null) {
            measureChildWithMargins(mHeaderView, widthMeasureSpec, 0, heightMeasureSpec, 0);
            MarginLayoutParams lp = (MarginLayoutParams) mHeaderView.getLayoutParams();
            mHeaderHeight = mHeaderView.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
            mPtrIndicator.setHeaderHeight(mHeaderHeight);
        }

        if (mFooterView != null) {
            measureChildWithMargins(mFooterView, widthMeasureSpec, 0, heightMeasureSpec, 0);
            MarginLayoutParams lp = (MarginLayoutParams) mFooterView.getLayoutParams();
            mFooterHeight = mFooterView.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
        }

        if (mContent != null) {
            measureContentView(mContent, widthMeasureSpec, heightMeasureSpec);
            if (isDebug()) {
                MarginLayoutParams lp = (MarginLayoutParams) mContent.getLayoutParams();
                PtrCLog.d(LOG_TAG, "onMeasure content, width: %s, height: %s, margin: %s %s %s %s",
                        getMeasuredWidth(), getMeasuredHeight(),
                        lp.leftMargin, lp.topMargin, lp.rightMargin, lp.bottomMargin);
                PtrCLog.d(LOG_TAG, "onMeasure, currentPos: %s, lastPos: %s, top: %s",
                        mPtrIndicator.getCurrentPosY(), mPtrIndicator.getLastPosY(), mContent.getTop());
            }
        }
    }

    private void measureContentView(View child,
                                    int parentWidthMeasureSpec,
                                    int parentHeightMeasureSpec) {
        final MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();

        final int childWidthMeasureSpec = getChildMeasureSpec(parentWidthMeasureSpec,
                getPaddingLeft() + getPaddingRight() + lp.leftMargin + lp.rightMargin, lp.width);
        final int childHeightMeasureSpec = getChildMeasureSpec(parentHeightMeasureSpec,
                getPaddingTop() + getPaddingBottom() + lp.topMargin, lp.height);

        child.measure(childWidthMeasureSpec, childHeightMeasureSpec);
    }

    @Override
    protected void onLayout(boolean flag, int i, int j, int k, int l) {
        layoutChildren();
    }

    private void layoutChildren() {
        int offset = mPtrIndicator.getCurrentPosY();
        int paddingLeft = getPaddingLeft();
        int paddingTop = getPaddingTop();

        if (mHeaderView != null) {
            MarginLayoutParams lp = (MarginLayoutParams) mHeaderView.getLayoutParams();
            final int left = paddingLeft + lp.leftMargin;
            // enhance readability(header is layout above screen when first init)
            final int top = -(mHeaderHeight - paddingTop - lp.topMargin - offset);
            final int right = left + mHeaderView.getMeasuredWidth();
            final int bottom = top + mHeaderView.getMeasuredHeight();
            mHeaderView.layout(left, top, right, bottom);
            if (isDebug()) {
                PtrCLog.d(LOG_TAG, "onLayout header: %s %s %s %s", left, top, right, bottom);
            }
        }
        if (mContent != null) {
            if (isPinContent()) {
                offset = 0;
            }
            MarginLayoutParams lp = (MarginLayoutParams) mContent.getLayoutParams();
            final int left = paddingLeft + lp.leftMargin;
            final int top = paddingTop + lp.topMargin + offset;
            final int right = left + mContent.getMeasuredWidth();
            final int bottom = top + mContent.getMeasuredHeight();
            if (isDebug()) {
                PtrCLog.d(LOG_TAG, "onLayout content: %s %s %s %s", left, top, right, bottom);
            }
            mContent.layout(left, top, right, bottom);
            if (mContentHeight == -1) {
                mContentHeight = bottom - top;
            }
        }
        if (mFooterView != null && mContent != null) {
            MarginLayoutParams lp = (MarginLayoutParams) mFooterView.getLayoutParams();
            final int top = mContentHeight;
            final int left = paddingLeft + lp.leftMargin;
            final int bottom = top + mFooterView.getMeasuredHeight();
            final int right = left + mFooterView.getMeasuredWidth();
            mFooterView.layout(left, top, right, bottom);
            if (isDebug()) {
                PtrCLog.d(LOG_TAG, "onLayout footer: %s %s %s %s", left, top, right, bottom);
            }
        }
    }

    @SuppressWarnings({"PointlessBooleanExpression", "ConstantConditions"})
    private boolean isDebug() {
        return DEBUG && DEBUG_LAYOUT;
    }

    public boolean dispatchTouchEventSupper(MotionEvent e) {
        return super.dispatchTouchEvent(e);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (!isEnabled() || mContent == null || mHeaderView == null) {
            return dispatchTouchEventSupper(e);
        }
        int action = e.getAction();
        switch (action) {
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mPtrIndicator.onRelease();
                if (mPtrIndicator.hasLeftStartPosition()) {
                    if (DEBUG) {
                        PtrCLog.d(LOG_TAG, "call onRelease when user release");
                    }
                    onRelease(false);
                    if (mPtrIndicator.hasMovedAfterPressedDown()) {
                        sendCancelEvent();
                        return true;
                    }
                    return dispatchTouchEventSupper(e);
                } else {
                    return dispatchTouchEventSupper(e);
                }

            case MotionEvent.ACTION_DOWN:
                mHasSendCancelEvent = false;
                mPtrIndicator.onPressDown(e.getX(), e.getY());

                mScrollChecker.abortIfWorking();

                mPreventForHorizontal = false;
                // The cancel event will be sent once the position is moved.
                // So let the event pass to children.
                // fix #93, #102
                dispatchTouchEventSupper(e);
                return true;

            case MotionEvent.ACTION_MOVE:
                mLastMoveEvent = e;
                mPtrIndicator.onMove(e.getX(), e.getY());
                float offsetX = mPtrIndicator.getOffsetX();
                float offsetY = mPtrIndicator.getOffsetY();
                if (Math.abs(offsetY) < 1) {
                    return true;
                }
                if (mDisableWhenHorizontalMove && !mPreventForHorizontal && (Math.abs(offsetX) > mPagingTouchSlop && Math.abs(offsetX) > Math.abs(offsetY))) {
                    if (mPtrIndicator.isInStartPosition()) {
                        mPreventForHorizontal = true;
                    }
                }
                if (mPreventForHorizontal) {
                    return dispatchTouchEventSupper(e);
                }
                boolean moveDown = offsetY > 0;
                boolean moveUp = !moveDown;
                if (moveDown) {
                    if (mLoadMoreStatus == LOAD_MORE_STATUS_LOADING && mFooterView.getTop() < mContentHeight) {
                        offsetY = Math.min(mContentHeight - mFooterView.getTop(), offsetY);
                        mContent.offsetTopAndBottom((int) offsetY);
                        mFooterView.offsetTopAndBottom((int) offsetY);
                        return true;
                    }
                    if (mContent.canScrollVertically(-1)) {
                        return mContent.dispatchTouchEvent(e);
                    }
                } else if (mPtrStatus != PTR_STATUS_PREPARE) {
                    if (mContent.canScrollVertically(1)) {
                        return mContent.dispatchTouchEvent(e);
                    }
                    if (mPtrStatus == PTR_STATUS_LOADING) {
                        return true;
                    }
                    if (mLoadMoreStatus == LOAD_MORE_STATUS_LOADING) {
                        if (mFooterView.getBottom() <= mContentHeight) {
                            return true;
                        }
                        offsetY = Math.max(mContentHeight - mFooterView.getBottom(), offsetY);
                        mContent.offsetTopAndBottom((int) offsetY);
                        mFooterView.offsetTopAndBottom((int) offsetY);
                        return true;
                    }
                    performLoadMore();
                }
                boolean canMoveUp = mPtrIndicator.hasLeftStartPosition();
                if (DEBUG) {
                    boolean canMoveDown = mPtrHandler != null && mPtrHandler.checkCanDoRefresh(this, mContent, mHeaderView);
                    PtrCLog.v(LOG_TAG, "ACTION_MOVE: offsetY:%s, currentPos: %s, moveUp: %s, canMoveUp: %s, moveDown: %s: canMoveDown: %s", offsetY, mPtrIndicator.getCurrentPosY(), moveUp, canMoveUp, moveDown, canMoveDown);
                }
                // disable move when header not reach top
                if (moveDown && mPtrHandler != null && !mPtrHandler.checkCanDoRefresh(this, mContent, mHeaderView)) {
                    return dispatchTouchEventSupper(e);
                }
                if ((moveUp && canMoveUp) || moveDown) {
                    movePos(offsetY);
                    return true;
                }
        }
        return dispatchTouchEventSupper(e);

    }

    public boolean isFooterVisible() {
        if (mFooterView == null) {
            return false;
        }
        return mFooterView.getTop() < mContentHeight;
    }

    /**
     * if deltaY > 0, move the content down
     *
     * @param deltaY
     */
    private void movePos(float deltaY) {
        // has reached the top
        if ((deltaY < 0 && mPtrIndicator.isInStartPosition())) {
            if (DEBUG) {
                PtrCLog.e(LOG_TAG, String.format("has reached the top"));
            }
            return;
        }

        int to = mPtrIndicator.getCurrentPosY() + (int) deltaY;

        // over top
        if (mPtrIndicator.willOverTop(to)) {
            if (DEBUG) {
                PtrCLog.e(LOG_TAG, String.format("over top"));
            }
            to = PtrIndicator.POS_START;
        }

        mPtrIndicator.setCurrentPos(to);
        int change = to - mPtrIndicator.getLastPosY();
        updatePos(change);
    }

    private void updatePos(int change) {
        if (change == 0) {
            return;
        }

        boolean isUnderTouch = mPtrIndicator.isUnderTouch();

        // once moved, cancel event will be sent to child
        if (isUnderTouch && !mHasSendCancelEvent && mPtrIndicator.hasMovedAfterPressedDown()) {
            mHasSendCancelEvent = true;
            sendCancelEvent();
        }

        // leave initiated position or just refresh complete
        if ((mPtrIndicator.hasJustLeftStartPosition() && mPtrStatus == PTR_STATUS_INIT) ||
                (mPtrIndicator.goDownCrossFinishPosition() && mPtrStatus == PTR_STATUS_COMPLETE && isEnabledNextPtrAtOnce())) {

            mPtrStatus = PTR_STATUS_PREPARE;
            mPtrUIHandlerHolder.onUIRefreshPrepare(this);
            if (DEBUG) {
                PtrCLog.i(LOG_TAG, "PtrUIHandler: onUIRefreshPrepare, mFlag %s", mFlag);
            }
        }

        // back to initiated position
        if (mPtrIndicator.hasJustBackToStartPosition()) {
            tryToNotifyReset();

            // recover event to children
            if (isUnderTouch) {
                sendDownEvent();
            }
        }

        // Pull to Refresh
        if (mPtrStatus == PTR_STATUS_PREPARE) {
            // reach fresh height while moving from top to bottom
            if (isUnderTouch && !isAutoRefresh() && mPullToRefresh
                    && mPtrIndicator.crossRefreshLineFromTopToBottom()) {
                tryToPerformRefresh();
            }
            // reach header height while auto refresh
            if (performAutoRefreshButLater() && mPtrIndicator.hasJustReachedHeaderHeightFromTopToBottom()) {
                tryToPerformRefresh();
            }
        }

        if (DEBUG) {
            PtrCLog.v(LOG_TAG, "updatePos: change: %s, current: %s last: %s, top: %s, headerHeight: %s",
                    change, mPtrIndicator.getCurrentPosY(), mPtrIndicator.getLastPosY(), mContent.getTop(), mHeaderHeight);
        }

        mHeaderView.offsetTopAndBottom(change);
        if (!isPinContent()) {
            mContent.offsetTopAndBottom(change);
        }
        invalidate();

        if (mPtrUIHandlerHolder.hasHandler()) {
            mPtrUIHandlerHolder.onUIPositionChange(this, isUnderTouch, mPtrStatus, mPtrIndicator);
        }
        onPositionChange(isUnderTouch, mPtrStatus, mPtrIndicator);
    }

    protected void onPositionChange(boolean isInTouching, byte status, PtrIndicator mPtrIndicator) {
    }

    @SuppressWarnings("unused")
    public int getHeaderHeight() {
        return mHeaderHeight;
    }

    private void onRelease(boolean stayForLoading) {

        tryToPerformRefresh();

        if (mPtrStatus == PTR_STATUS_LOADING) {
            // keep header for fresh
            if (mKeepHeaderWhenRefresh) {
                // scroll header back
                if (mPtrIndicator.isOverOffsetToKeepHeaderWhileLoading() && !stayForLoading) {
                    mScrollChecker.tryToScrollTo(mPtrIndicator.getOffsetToKeepHeaderWhileLoading(), mDurationToClose);
                } else {
                    // do nothing
                }
            } else {
                tryScrollBackToTopWhileLoading();
            }
        } else {
            if (mPtrStatus == PTR_STATUS_COMPLETE) {
                notifyUIRefreshComplete(false);
            } else {
                tryScrollBackToTopAbortRefresh();
            }
        }
    }

    /**
     * please DO REMEMBER resume the hook
     *
     * @param hook
     */

    public void setRefreshCompleteHook(PtrUIHandlerHook hook) {
        mRefreshCompleteHook = hook;
        hook.setResumeAction(new Runnable() {
            @Override
            public void run() {
                if (DEBUG) {
                    PtrCLog.d(LOG_TAG, "mRefreshCompleteHook resume.");
                }
                notifyUIRefreshComplete(true);
            }
        });
    }

    /**
     * Scroll back to to if is not under touch
     */
    private void tryScrollBackToTop() {
        if (!mPtrIndicator.isUnderTouch()) {
            LayoutParams layoutParams = (LayoutParams) mContent.getLayoutParams();
            layoutParams.height = mContentHeight;
            mScrollChecker.tryToScrollTo(PtrIndicator.POS_START, mDurationToCloseHeader);
        }
    }

    /**
     * just make easier to understand
     */
    private void tryScrollBackToTopWhileLoading() {
        tryScrollBackToTop();
    }

    /**
     * just make easier to understand
     */
    private void tryScrollBackToTopAfterComplete() {
        tryScrollBackToTop();
    }

    /**
     * just make easier to understand
     */
    private void tryScrollBackToTopAbortRefresh() {
        tryScrollBackToTop();
    }

    private boolean tryToPerformRefresh() {
        if (mPtrStatus != PTR_STATUS_PREPARE) {
            return false;
        }

        //
        if ((mPtrIndicator.isOverOffsetToKeepHeaderWhileLoading() && isAutoRefresh()) || mPtrIndicator.isOverOffsetToRefresh()) {
            mPtrStatus = PTR_STATUS_LOADING;
            performRefresh();
        }
        return false;
    }

    private void performRefresh() {
        if (mLoadMoreStatus == LOAD_MORE_STATUS_LOADING) {
            performLoadMoreCancel();
        }
        LayoutParams layoutParams = (LayoutParams) mContent.getLayoutParams();
        layoutParams.height = mContentHeight - mHeaderHeight;
        mLoadingStartTime = System.currentTimeMillis();
        if (mPtrUIHandlerHolder.hasHandler()) {
            mPtrUIHandlerHolder.onUIRefreshBegin(this);
            if (DEBUG) {
                PtrCLog.i(LOG_TAG, "PtrUIHandler: onUIRefreshBegin");
            }
        }
        if (mPtrHandler != null) {
            mPtrHandler.onRefreshBegin(this);
        }
    }

    private void performLoadMoreCancel() {
        mLoadMoreStatus = LOAD_MORE_STATUS_INIT;
        mFooterView.setVisibility(INVISIBLE);
        if (mLoadMoreUIHandlerHolder.hasHandler()) {
            mLoadMoreUIHandlerHolder.onUILoadMoreCancel(this);
            if (DEBUG) {
                PtrCLog.i(LOG_TAG, "LoadMoreUIHandler: onUILoadMoreCancel");
            }
        }
        if (mLoadMoreHandler != null) {
            mLoadMoreHandler.onLoadMoreCancel(this);
        }
    }

    private void performLoadMore() {
        if (mLoadMoreStatus == LOAD_MORE_STATUS_LOADING || mPtrStatus == PTR_STATUS_LOADING) {
            return;
        }
        if (mLoadMoreHandler != null && !mLoadMoreHandler.checkCanDoLoadMore(this, mContent, mFooterView)) {
            return;
        }
        mFooterView.setVisibility(VISIBLE);
        mLoadMoreStatus = LOAD_MORE_STATUS_LOADING;
        mContent.offsetTopAndBottom(-mFooterHeight);
        mFooterView.offsetTopAndBottom(-mFooterHeight);
        mLoadingStartTime = System.currentTimeMillis();
        if (mLoadMoreUIHandlerHolder.hasHandler()) {
            mLoadMoreUIHandlerHolder.onUILoadMoreBegin(this);
            if (DEBUG) {
                PtrCLog.i(LOG_TAG, "LoadMoreUIHandler: onUILoadMoreBegin");
            }
        }
        if (mLoadMoreHandler != null) {
            mLoadMoreHandler.onLoadMoreBegin(this);
        }
    }

    /**
     * If at the top and not in loading, reset
     */
    private boolean tryToNotifyReset() {
        if ((mPtrStatus == PTR_STATUS_COMPLETE || mPtrStatus == PTR_STATUS_PREPARE) && mPtrIndicator.isInStartPosition()) {
            if (mPtrUIHandlerHolder.hasHandler()) {
                mPtrUIHandlerHolder.onUIReset(this);
                if (DEBUG) {
                    PtrCLog.i(LOG_TAG, "PtrUIHandler: onUIReset");
                }
            }
            mPtrStatus = PTR_STATUS_INIT;
            clearFlag();
            return true;
        }
        return false;
    }

    protected void onPtrScrollAbort() {
        if (mPtrIndicator.hasLeftStartPosition() && isAutoRefresh()) {
            if (DEBUG) {
                PtrCLog.d(LOG_TAG, "call onRelease after scroll abort");
            }
            onRelease(true);
        }
    }

    protected void onPtrScrollFinish() {
        if (mPtrIndicator.hasLeftStartPosition() && isAutoRefresh()) {
            if (DEBUG) {
                PtrCLog.d(LOG_TAG, "call onRelease after scroll finish");
            }
            onRelease(true);
        }
    }

    /**
     * Detect whether is refreshing.
     *
     * @return
     */
    public boolean isRefreshing() {
        return mPtrStatus == PTR_STATUS_LOADING;
    }

    /**
     * Call this when data is loaded.
     * The UI will perform complete at once or after a delay, depends on the time elapsed is greater then {@link #mLoadingMinTime} or not.
     */
    final public void refreshComplete() {
        if (DEBUG) {
            PtrCLog.i(LOG_TAG, "refreshComplete");
        }

        if (mRefreshCompleteHook != null) {
            mRefreshCompleteHook.reset();
        }

        int delay = (int) (mLoadingMinTime - (System.currentTimeMillis() - mLoadingStartTime));
        if (delay <= 0) {
            if (DEBUG) {
                PtrCLog.d(LOG_TAG, "performRefreshComplete at once");
            }
            performRefreshComplete();
        } else {
            postDelayed(mPerformRefreshCompleteDelay, delay);
            if (DEBUG) {
                PtrCLog.d(LOG_TAG, "performRefreshComplete after delay: %s", delay);
            }
        }
    }

    public void loadMoreComplete() {
        if (DEBUG) {
            PtrCLog.i(LOG_TAG, "loadMoreComplete");
        }

        int delay = (int) (mLoadingMinTime - (System.currentTimeMillis() - mLoadingStartTime));
        if (delay <= 0) {
            if (DEBUG) {
                PtrCLog.d(LOG_TAG, "performLoadMoreComplete at once");
            }
            performLoadMoreComplete();
        } else {
            postDelayed(mPerformLoadMoreCompleteDelay, delay);
            if (DEBUG) {
                PtrCLog.d(LOG_TAG, "performLoadMoreComplete after delay: %s", delay);
            }
        }
    }

    /**
     * Do refresh complete work when time elapsed is greater than {@link #mLoadingMinTime}
     */
    private void performRefreshComplete() {
        mPtrStatus = PTR_STATUS_COMPLETE;

        // if is auto refresh do nothing, wait scroller stop
        if (mScrollChecker.mIsRunning && isAutoRefresh()) {
            // do nothing
            if (DEBUG) {
                PtrCLog.d(LOG_TAG, "performRefreshComplete do nothing, scrolling: %s, auto refresh: %s",
                        mScrollChecker.mIsRunning, mFlag);
            }
            return;
        }

        notifyUIRefreshComplete(false);
    }

    private void performLoadMoreComplete() {
        if (mLoadMoreStatus != LOAD_MORE_STATUS_LOADING) {
            return;
        }
        mLoadMoreStatus = LOAD_MORE_STATUS_COMPLETE;
        mFooterView.setVisibility(INVISIBLE);
        int offset = mContentHeight - mContent.getBottom();
        mContent.offsetTopAndBottom(offset);
        mFooterView.offsetTopAndBottom(offset);
    }

    /**
     * Do real refresh work. If there is a hook, execute the hook first.
     *
     * @param ignoreHook
     */
    private void notifyUIRefreshComplete(boolean ignoreHook) {
        /**
         * After hook operation is done, {@link #notifyUIRefreshComplete} will be call in resume action to ignore hook.
         */
        if (mPtrIndicator.hasLeftStartPosition() && !ignoreHook && mRefreshCompleteHook != null) {
            if (DEBUG) {
                PtrCLog.d(LOG_TAG, "notifyUIRefreshComplete mRefreshCompleteHook run.");
            }

            mRefreshCompleteHook.takeOver();
            return;
        }
        if (mPtrUIHandlerHolder.hasHandler()) {
            if (DEBUG) {
                PtrCLog.i(LOG_TAG, "PtrUIHandler: onUIRefreshComplete");
            }
            mPtrUIHandlerHolder.onUIRefreshComplete(this);
        }
        mPtrIndicator.onUIRefreshComplete();
        tryScrollBackToTopAfterComplete();
        tryToNotifyReset();
    }

    public void autoRefresh() {
        autoRefresh(true, mDurationToCloseHeader);
    }

    public void autoRefresh(boolean atOnce) {
        autoRefresh(atOnce, mDurationToCloseHeader);
    }

    private void clearFlag() {
        // remove auto fresh flag
        mFlag = mFlag & ~MASK_AUTO_REFRESH;
        mFlag = mFlag & ~MASK_AUTO_LOAD_MORE;
    }

    public void autoRefresh(boolean atOnce, int duration) {

        if (mPtrStatus != PTR_STATUS_INIT) {
            return;
        }

        mFlag |= atOnce ? FLAG_AUTO_REFRESH_AT_ONCE : FLAG_AUTO_REFRESH_BUT_LATER;

        mPtrStatus = PTR_STATUS_PREPARE;
        if (mPtrUIHandlerHolder.hasHandler()) {
            mPtrUIHandlerHolder.onUIRefreshPrepare(this);
            if (DEBUG) {
                PtrCLog.i(LOG_TAG, "PtrUIHandler: onUIRefreshPrepare, mFlag %s", mFlag);
            }
        }
        mScrollChecker.tryToScrollTo(mPtrIndicator.getOffsetToRefresh(), duration);
        if (atOnce) {
            mPtrStatus = PTR_STATUS_LOADING;
            performRefresh();
        }
    }

    public boolean isAutoRefresh() {
        return (mFlag & MASK_AUTO_REFRESH) > 0;
    }

    public boolean isAutoLoadMore() {
        return (mFlag & MASK_AUTO_LOAD_MORE) > 0;
    }

    private boolean performAutoRefreshButLater() {
        return (mFlag & MASK_AUTO_REFRESH) == FLAG_AUTO_REFRESH_BUT_LATER;
    }

    public boolean isEnabledNextPtrAtOnce() {
        return (mFlag & FLAG_ENABLE_NEXT_PTR_AT_ONCE) > 0;
    }

    /**
     * If @param enable has been set to true. The user can perform next PTR at once.
     *
     * @param enable
     */
    public void setEnabledNextPtrAtOnce(boolean enable) {
        if (enable) {
            mFlag = mFlag | FLAG_ENABLE_NEXT_PTR_AT_ONCE;
        } else {
            mFlag = mFlag & ~FLAG_ENABLE_NEXT_PTR_AT_ONCE;
        }
    }

    public boolean isPinContent() {
        return (mFlag & FLAG_PIN_CONTENT) > 0;
    }

    /**
     * The content view will now move when {@param pinContent} set to true.
     *
     * @param pinContent
     */
    public void setPinContent(boolean pinContent) {
        if (pinContent) {
            mFlag = mFlag | FLAG_PIN_CONTENT;
        } else {
            mFlag = mFlag & ~FLAG_PIN_CONTENT;
        }
    }

    /**
     * It's useful when working with viewpager.
     *
     * @param disable
     */
    public void disableWhenHorizontalMove(boolean disable) {
        mDisableWhenHorizontalMove = disable;
    }

    /**
     * loading will last at least for so long
     *
     * @param time
     */
    public void setLoadingMinTime(int time) {
        mLoadingMinTime = time;
    }

    /**
     * Not necessary any longer. Once moved, cancel event will be sent to child.
     *
     * @param yes
     */
    @Deprecated
    public void setInterceptEventWhileWorking(boolean yes) {
    }

    @SuppressWarnings({"unused"})
    public View getContentView() {
        return mContent;
    }

    public void setPtrHandler(PtrHandler ptrHandler) {
        mPtrHandler = ptrHandler;
    }

    public void setLoadMoreHandler(LoadMoreHandler loadMoreHandler) {
        mLoadMoreHandler = loadMoreHandler;
    }

    public void addPtrUIHandler(PtrUIHandler ptrUIHandler) {
        PtrUIHandlerHolder.addHandler(mPtrUIHandlerHolder, ptrUIHandler);
    }

    public void addLoadMoreUIHandler(LoadMoreUIHandler loadMoreUIHandler) {
        LoadMoreUIHandlerHolder.addHandler(mLoadMoreUIHandlerHolder, loadMoreUIHandler);
    }

    @SuppressWarnings({"unused"})
    public void removePtrUIHandler(PtrUIHandler ptrUIHandler) {
        mPtrUIHandlerHolder = PtrUIHandlerHolder.removeHandler(mPtrUIHandlerHolder, ptrUIHandler);
    }

    public void removeLoadMoreUIHandler(LoadMoreUIHandler loadMoreUIHandler) {
        mLoadMoreUIHandlerHolder = LoadMoreUIHandlerHolder.removeHandler(mLoadMoreUIHandlerHolder, loadMoreUIHandler);
    }

    public void setPtrIndicator(PtrIndicator slider) {
        if (mPtrIndicator != null && mPtrIndicator != slider) {
            slider.convertFrom(mPtrIndicator);
        }
        mPtrIndicator = slider;
    }

    @SuppressWarnings({"unused"})
    public float getResistance() {
        return mPtrIndicator.getResistance();
    }

    public void setResistance(float resistance) {
        mPtrIndicator.setResistance(resistance);
    }

    @SuppressWarnings({"unused"})
    public float getDurationToClose() {
        return mDurationToClose;
    }

    /**
     * The duration to return back to the refresh position
     *
     * @param duration
     */
    public void setDurationToClose(int duration) {
        mDurationToClose = duration;
    }

    @SuppressWarnings({"unused"})
    public long getDurationToCloseHeader() {
        return mDurationToCloseHeader;
    }

    /**
     * The duration to close time
     *
     * @param duration
     */
    public void setDurationToCloseHeader(int duration) {
        mDurationToCloseHeader = duration;
    }

    public void setRatioOfHeaderHeightToRefresh(float ratio) {
        mPtrIndicator.setRatioOfHeaderHeightToRefresh(ratio);
    }

    public int getOffsetToRefresh() {
        return mPtrIndicator.getOffsetToRefresh();
    }

    @SuppressWarnings({"unused"})
    public void setOffsetToRefresh(int offset) {
        mPtrIndicator.setOffsetToRefresh(offset);
    }

    @SuppressWarnings({"unused"})
    public float getRatioOfHeaderToHeightRefresh() {
        return mPtrIndicator.getRatioOfHeaderToHeightRefresh();
    }

    @SuppressWarnings({"unused"})
    public int getOffsetToKeepHeaderWhileLoading() {
        return mPtrIndicator.getOffsetToKeepHeaderWhileLoading();
    }

    @SuppressWarnings({"unused"})
    public void setOffsetToKeepHeaderWhileLoading(int offset) {
        mPtrIndicator.setOffsetToKeepHeaderWhileLoading(offset);
    }

    @SuppressWarnings({"unused"})
    public boolean isKeepHeaderWhenRefresh() {
        return mKeepHeaderWhenRefresh;
    }

    public void setKeepHeaderWhenRefresh(boolean keepOrNot) {
        mKeepHeaderWhenRefresh = keepOrNot;
    }

    public boolean isPullToRefresh() {
        return mPullToRefresh;
    }

    public void setPullToRefresh(boolean pullToRefresh) {
        mPullToRefresh = pullToRefresh;
    }

    @SuppressWarnings({"unused"})
    public View getHeaderView() {
        return mHeaderView;
    }

    public void setHeaderView(View header) {
        if (mHeaderView != null && header != null && mHeaderView != header) {
            removeView(mHeaderView);
        }
        ViewGroup.LayoutParams lp = header.getLayoutParams();
        if (lp == null) {
            lp = new LayoutParams(-1, -2);
            header.setLayoutParams(lp);
        }
        mHeaderView = header;
        addView(header);
    }

    public View getFooterView() {
        return mFooterView;
    }

    public void setFooterView(View footer) {
        if (mFooterView != null && footer != null && mFooterView != footer) {
            removeView(mFooterView);
        }
        ViewGroup.LayoutParams lp = footer.getLayoutParams();
        if (lp == null) {
            lp = new LayoutParams(-1, -2);
            footer.setLayoutParams(lp);
        }
        mFooterView = footer;
        mFooterView.setVisibility(INVISIBLE);
        addView(footer);
    }

    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return p != null && p instanceof LayoutParams;
    }

    @Override
    protected ViewGroup.LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
    }

    @Override
    protected ViewGroup.LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
        return new LayoutParams(p);
    }

    @Override
    public ViewGroup.LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new LayoutParams(getContext(), attrs);
    }

    private void sendCancelEvent() {
        if (DEBUG) {
            PtrCLog.d(LOG_TAG, "send cancel event");
        }
        // The ScrollChecker will update position and lead to send cancel event when mLastMoveEvent is null.
        // fix #104, #80, #92
        if (mLastMoveEvent == null) {
            return;
        }
        MotionEvent last = mLastMoveEvent;
        MotionEvent e = MotionEvent.obtain(last.getDownTime(), last.getEventTime() + ViewConfiguration.getLongPressTimeout(), MotionEvent.ACTION_CANCEL, last.getX(), last.getY(), last.getMetaState());
        dispatchTouchEventSupper(e);
    }

    private void sendDownEvent() {
        if (DEBUG) {
            PtrCLog.d(LOG_TAG, "send down event");
        }
        final MotionEvent last = mLastMoveEvent;
        MotionEvent e = MotionEvent.obtain(last.getDownTime(), last.getEventTime(), MotionEvent.ACTION_DOWN, last.getX(), last.getY(), last.getMetaState());
        dispatchTouchEventSupper(e);
    }

    public static class LayoutParams extends MarginLayoutParams {

        public LayoutParams(Context c, AttributeSet attrs) {
            super(c, attrs);
        }

        public LayoutParams(int width, int height) {
            super(width, height);
        }

        @SuppressWarnings({"unused"})
        public LayoutParams(MarginLayoutParams source) {
            super(source);
        }

        public LayoutParams(ViewGroup.LayoutParams source) {
            super(source);
        }
    }

    class ScrollChecker implements Runnable {

        private int mLastFlingY;
        private Scroller mScroller;
        private boolean mIsRunning = false;
        private int mStart;
        private int mTo;

        public ScrollChecker() {
            mScroller = new Scroller(getContext());
        }

        public void run() {
            boolean finish = !mScroller.computeScrollOffset() || mScroller.isFinished();
            int curY = mScroller.getCurrY();
            int deltaY = curY - mLastFlingY;
            if (DEBUG) {
                if (deltaY != 0) {
                    PtrCLog.v(LOG_TAG,
                            "scroll: %s, start: %s, to: %s, currentPos: %s, current :%s, last: %s, delta: %s",
                            finish, mStart, mTo, mPtrIndicator.getCurrentPosY(), curY, mLastFlingY, deltaY);
                }
            }
            if (!finish) {
                mLastFlingY = curY;
                movePos(deltaY);
                post(this);
            } else {
                finish();
            }
        }

        private void finish() {
            if (DEBUG) {
                PtrCLog.v(LOG_TAG, "finish, currentPos:%s", mPtrIndicator.getCurrentPosY());
            }
            reset();
            onPtrScrollFinish();
        }

        private void reset() {
            mIsRunning = false;
            mLastFlingY = 0;
            removeCallbacks(this);
        }

        private void destroy() {
            reset();
            if (!mScroller.isFinished()) {
                mScroller.forceFinished(true);
            }
        }

        public void abortIfWorking() {
            if (mIsRunning) {
                if (!mScroller.isFinished()) {
                    mScroller.forceFinished(true);
                }
                onPtrScrollAbort();
                reset();
            }
        }

        public void tryToScrollTo(int to, int duration) {
            if (mPtrIndicator.isAlreadyHere(to)) {
                return;
            }
            mStart = mPtrIndicator.getCurrentPosY();
            mTo = to;
            int distance = to - mStart;
            if (DEBUG) {
                PtrCLog.d(LOG_TAG, "tryToScrollTo: start: %s, distance:%s, to:%s", mStart, distance, to);
            }
            removeCallbacks(this);

            mLastFlingY = 0;

            // fix #47: Scroller should be reused, https://github.com/liaohuqiu/android-Ultra-Pull-To-Refresh/issues/47
            if (!mScroller.isFinished()) {
                mScroller.forceFinished(true);
            }
            mScroller.startScroll(0, 0, 0, distance, duration);
            post(this);
            mIsRunning = true;
        }
    }
}
