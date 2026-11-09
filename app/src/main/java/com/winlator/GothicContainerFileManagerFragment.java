package com.winlator;

/** Container file manager used by the Gothic launcher. Shortcut creation and storage
 * are handled by ContainerFileManagerFragment so paths stay inside the selected container. */
public class GothicContainerFileManagerFragment extends ContainerFileManagerFragment {
    public GothicContainerFileManagerFragment() {
        super();
    }

    public GothicContainerFileManagerFragment(int containerId) {
        super(containerId);
    }

    public GothicContainerFileManagerFragment(int containerId, String startPath) {
        super(containerId, startPath);
    }
}
