//! Serialized backend lifecycle: setup, preferences, and launch cannot overlap.

use std::sync::Mutex;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Lifecycle {
    Idle,
    Refreshing,
    SettingUp,
    Saving,
    PreferenceUncertain { desired: bool },
    Launching,
    Active,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LifecycleError {
    Busy,
    Active,
    PreferenceUncertain,
}

pub struct Coordinator {
    state: Lifecycle,
    launch_generation: u64,
}

impl Default for Coordinator {
    fn default() -> Self {
        Coordinator {
            state: Lifecycle::Idle,
            launch_generation: 0,
        }
    }
}

impl Coordinator {
    pub fn state(&self) -> Lifecycle {
        self.state
    }

    pub fn generation(&self) -> u64 {
        self.launch_generation
    }

    pub fn try_refresh(&mut self) -> Result<(), LifecycleError> {
        self.claim(Lifecycle::Refreshing)
    }

    pub fn try_setup(&mut self) -> Result<(), LifecycleError> {
        self.claim(Lifecycle::SettingUp)
    }

    pub fn try_save(&mut self) -> Result<(), LifecycleError> {
        if matches!(self.state, Lifecycle::PreferenceUncertain { .. }) {
            return Err(LifecycleError::PreferenceUncertain);
        }
        self.claim(Lifecycle::Saving)
    }

    pub fn try_save_while_uncertain(&mut self, desired: bool) -> Result<(), LifecycleError> {
        match self.state {
            Lifecycle::PreferenceUncertain { desired: current } if current == desired => {
                self.state = Lifecycle::Saving;
                Ok(())
            }
            Lifecycle::PreferenceUncertain { .. } => Err(LifecycleError::PreferenceUncertain),
            _ => self.claim(Lifecycle::Saving),
        }
    }

    pub fn try_launch(&mut self) -> Result<u64, LifecycleError> {
        if self.state == Lifecycle::Active {
            return Err(LifecycleError::Active);
        }
        if matches!(self.state, Lifecycle::PreferenceUncertain { .. }) {
            return Err(LifecycleError::PreferenceUncertain);
        }
        match self.state {
            Lifecycle::Idle => {
                self.launch_generation = self.launch_generation.wrapping_add(1);
                let gen = self.launch_generation;
                self.state = Lifecycle::Launching;
                Ok(gen)
            }
            _ => Err(LifecycleError::Busy),
        }
    }

    pub fn launch_active(&mut self) {
        self.state = Lifecycle::Active;
    }

    pub fn release(&mut self) {
        self.state = Lifecycle::Idle;
    }

    pub fn mark_uncertain(&mut self, desired: bool) {
        self.state = Lifecycle::PreferenceUncertain { desired };
    }

    pub fn clear_uncertain_on_success(&mut self) {
        if matches!(self.state, Lifecycle::PreferenceUncertain { .. }) {
            self.state = Lifecycle::Idle;
        }
    }

    fn claim(&mut self, next: Lifecycle) -> Result<(), LifecycleError> {
        match self.state {
            Lifecycle::Idle => {
                self.state = next;
                Ok(())
            }
            Lifecycle::PreferenceUncertain { .. } => Err(LifecycleError::PreferenceUncertain),
            Lifecycle::Active => Err(LifecycleError::Active),
            _ => Err(LifecycleError::Busy),
        }
    }
}

pub type SharedCoordinator = Mutex<Coordinator>;

/// Releases the coordinator back to [`Lifecycle::Idle`] on drop unless disarmed.
pub struct ReleaseOnDrop<'a> {
    coordinator: &'a mut Coordinator,
    armed: bool,
}

impl<'a> ReleaseOnDrop<'a> {
    pub fn new(coordinator: &'a mut Coordinator) -> Self {
        ReleaseOnDrop {
            coordinator,
            armed: true,
        }
    }

    pub fn disarm(&mut self) {
        self.armed = false;
    }
}

impl Drop for ReleaseOnDrop<'_> {
    fn drop(&mut self) {
        if self.armed {
            self.coordinator.release();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn overlapping_operations_rejected() {
        let mut c = Coordinator::default();
        assert!(c.try_refresh().is_ok());
        assert_eq!(c.try_launch(), Err(LifecycleError::Busy));
        c.release();
        assert!(c.try_launch().is_ok());
        assert_eq!(c.try_refresh(), Err(LifecycleError::Busy));
    }

    #[test]
    fn save_rejected_while_refreshing() {
        let mut c = Coordinator::default();
        assert!(c.try_refresh().is_ok());
        assert_eq!(c.try_save(), Err(LifecycleError::Busy));
        c.release();
    }

    #[test]
    fn uncertain_blocks_mismatched_save() {
        let mut c = Coordinator::default();
        c.mark_uncertain(true);
        assert_eq!(c.try_save(), Err(LifecycleError::PreferenceUncertain));
        assert!(c.try_save_while_uncertain(false).is_err());
        assert!(c.try_save_while_uncertain(true).is_ok());
    }

    #[test]
    fn uncertain_blocks_launch_until_cleared() {
        let mut c = Coordinator::default();
        c.mark_uncertain(false);
        assert_eq!(c.try_launch(), Err(LifecycleError::PreferenceUncertain));
        c.clear_uncertain_on_success();
        assert!(c.try_launch().is_ok());
    }

    #[test]
    fn setup_rejected_while_refreshing() {
        let mut c = Coordinator::default();
        assert!(c.try_refresh().is_ok());
        assert_eq!(c.try_setup(), Err(LifecycleError::Busy));
        c.release();
        assert!(c.try_setup().is_ok());
        c.release();
    }

    #[test]
    fn uncertain_launch_maps_to_rejection_not_queue() {
        let mut c = Coordinator::default();
        c.mark_uncertain(true);
        assert_eq!(c.try_launch(), Err(LifecycleError::PreferenceUncertain));
    }
}
