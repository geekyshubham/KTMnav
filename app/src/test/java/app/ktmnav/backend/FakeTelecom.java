package app.ktmnav.backend;

final class FakeTelecom implements CallControl.Telecom {
    boolean phoneState = true;
    boolean answerPermission = true;
    boolean acceptResult = true;
    boolean silenceResult = true;
    int accepted;
    int silenced;
    int restored;

    @Override
    public boolean canReadPhoneState() {
        return phoneState;
    }

    @Override
    public boolean canAnswerCalls() {
        return answerPermission;
    }

    @Override
    public boolean acceptRingingCall() {
        accepted++;
        return acceptResult;
    }

    @Override
    public boolean silenceRinger() {
        silenced++;
        return silenceResult;
    }

    @Override
    public void restoreRinger() {
        restored++;
    }
}
