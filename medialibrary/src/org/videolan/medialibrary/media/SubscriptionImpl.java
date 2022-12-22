package org.videolan.medialibrary.media;

import androidx.annotation.Nullable;

import org.videolan.medialibrary.interfaces.Medialibrary;
import org.videolan.medialibrary.interfaces.media.MediaWrapper;
import org.videolan.medialibrary.interfaces.media.MlService;
import org.videolan.medialibrary.interfaces.media.Subscription;

public class SubscriptionImpl extends Subscription {

    SubscriptionImpl(long id, MlService.Type type, String name, long parentId) {
        super(id, type, name, parentId);
    }

    SubscriptionImpl(long id, int type, String name, long parentId) {
        super(id, type, name, parentId);
    }

    @Override
    public int getNewMediaNotification() {
        final Medialibrary ml = Medialibrary.getInstance();
        return ml.isInitiated() ? nativeSubscriptionNewMediaNotification(ml, this.id) : -1;
    }

    @Override
    public boolean setNewMediaNotification(int value) {
        final Medialibrary ml = Medialibrary.getInstance();
        return ml.isInitiated() && nativeSetSubscriptionNewMediaNotification(ml, this.id, value);
    }

    @Override
    public long getCachedSize() {
        final Medialibrary ml = Medialibrary.getInstance();
        return ml.isInitiated() ? nativeGetSubscriptionCachedSize(ml, this.id) : -2;
    }

    @Override
    public long getMaxCachedSize() {
        final Medialibrary ml = Medialibrary.getInstance();
        return ml.isInitiated() ? nativeGetSubscriptionMaxCachedSize(ml, this.id) : -2;
    }

    @Override
    public boolean setMaxCachedSize(long size) {
        final Medialibrary ml = Medialibrary.getInstance();
        return ml.isInitiated() && nativeSetSubscriptionMaxCachedSize(ml, this.id, size);
    }

    @Override
    public int getNbUnplayedMedia() {
        final Medialibrary ml = Medialibrary.getInstance();
        return ml.isInitiated() ? nativeGetSubscriptionNbUnplayedMedia(ml, this.id) : -1;
    }

    @Override
    public Subscription[] getChildSubscriptions(int sortingCriteria, boolean desc, boolean includeMissing) {
        final Medialibrary ml = Medialibrary.getInstance();
        return ml.isInitiated() ? nativeGetChildSubscriptions(ml, id, sortingCriteria, desc, includeMissing) : new Subscription[0];
    }

    @Override
    @Nullable
    public Subscription getParent() {
        final Medialibrary ml = Medialibrary.getInstance();
        return ml.isInitiated() ? nativeGetParent(ml, id) : null;
    }

    @Override
    public boolean refresh() {
        final Medialibrary ml = Medialibrary.getInstance();
        return ml.isInitiated() && nativeSubscriptionRefresh(ml, id);
    }

    @Override
    public MediaWrapper[] getMedia(int sortingCriteria, boolean desc, boolean includeMissing) {
        final Medialibrary ml = Medialibrary.getInstance();
        return ml.isInitiated() ? nativeGetSubscriptionMedia(ml, id, sortingCriteria, desc, includeMissing) : Medialibrary.EMPTY_COLLECTION;
    }

    @Override
    public int getNbMedia() {
        final Medialibrary ml = Medialibrary.getInstance();
        return ml.isInitiated() ? nativeGetSubscriptionNbMedia(ml, id) : -1;
    }

    private native int nativeSubscriptionNewMediaNotification(Medialibrary ml, long id);
    private native boolean nativeSetSubscriptionNewMediaNotification(Medialibrary ml, long id, int value);
    private native long nativeGetSubscriptionCachedSize(Medialibrary ml, long id);
    private native long nativeGetSubscriptionMaxCachedSize(Medialibrary ml, long id);
    private native boolean nativeSetSubscriptionMaxCachedSize(Medialibrary ml, long id, long size);
    private native int nativeGetSubscriptionNbUnplayedMedia(Medialibrary ml, long id);
    private native Subscription[] nativeGetChildSubscriptions(Medialibrary ml, long id, int sortingCriteria, boolean desc, boolean includeMissing);
    private native Subscription nativeGetParent(Medialibrary ml, long id);
    private native boolean nativeSubscriptionRefresh(Medialibrary ml, long id);
    private native MediaWrapper[] nativeGetSubscriptionMedia(Medialibrary ml, long id, int sortingCriteria, boolean desc, boolean includeMissing);
    private native int nativeGetSubscriptionNbMedia(Medialibrary ml, long id);
}
