package ua.com.radiokot.photoprism.features.gallery.search.albums.view.model

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.subjects.BehaviorSubject
import io.reactivex.rxjava3.subjects.PublishSubject
import ua.com.radiokot.photoprism.extension.autoDispose
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.extension.toMainThreadObservable
import ua.com.radiokot.photoprism.features.gallery.data.storage.SearchPreferences
import ua.com.radiokot.photoprism.features.gallery.search.albums.data.storage.AlbumsRepository

/**
 * A viewmodel that controls list of selectable albums for the gallery search.
 *
 * @see update
 * @see selectedAlbumUid
 */
class GallerySearchAlbumsViewModel(
    private val albumsRepository: AlbumsRepository,
    searchPreferences: SearchPreferences,
) : ViewModel() {
    private val log = kLogger("GallerySearchAlbumsVM")

    private val stateSubject = BehaviorSubject.createDefault<State>(State.Loading)
    val state = stateSubject.toMainThreadObservable()
    private val eventsSubject = PublishSubject.create<Event>()
    val events = eventsSubject.toMainThreadObservable()
    val selectedAlbumUid = MutableLiveData<String?>()
    val isViewVisible = searchPreferences.showAlbums.toMainThreadObservable()

    init {
        subscribeToRepository()
        subscribeToAlbumSelection()
    }

    fun update(force: Boolean = false) {
        log.debug {
            "update(): updating:" +
                    "\nforce=$force"
        }

        if (force) {
            albumsRepository.update()
        } else {
            albumsRepository.updateIfNotFresh()
        }
    }

    private fun subscribeToRepository() {
        albumsRepository.items
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { albums ->
                if (albums.isEmpty() && albumsRepository.isNeverUpdated) {
                    stateSubject.onNext(State.Loading)
                } else {
                    postReadyState()
                }
            }
            .autoDispose(this)

        albumsRepository.errors
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { error ->
                log.error(error) {
                    "subscribeToRepository(): albums_loading_failed"
                }

                stateSubject.onNext(State.LoadingFailed)
            }
            .autoDispose(this)
    }

    private fun postReadyState() {
        val repositoryAlbums = albumsRepository.itemsList

        val selectedAlbumUid = selectedAlbumUid.value

        log.debug {
            "postReadyState(): posting_ready_state:" +
                    "\nalbumsCount=${repositoryAlbums.size}," +
                    "\nselectedAlbumUid=$selectedAlbumUid"
        }

        stateSubject.onNext(
            State.Ready(
                albums = repositoryAlbums.map { album ->
                    AlbumListItem(
                        source = album,
                        isAlbumSelected = album.uid == selectedAlbumUid
                    )
                }
            ))
    }

    private fun subscribeToAlbumSelection() {
        selectedAlbumUid.observeForever {
            val currentState = stateSubject.value
            if (currentState is State.Ready) {
                postReadyState()
            }
        }
    }

    fun onAlbumItemClicked(item: AlbumListItem) {
        val currentState = stateSubject.value
        check(currentState is State.Ready) {
            "Albums are clickable only in the ready state"
        }

        log.debug {
            "onAlbumItemClicked(): album_item_clicked:" +
                    "\nitem=$item"
        }

        if (item.source != null) {
            val uid = item.source.uid

            val newSelectedAlbumUid: String? =
                if (selectedAlbumUid.value != uid)
                    uid
                else
                    null

            log.debug {
                "onAlbumItemClicked(): setting_selected_album_uid:" +
                        "\nnewUid=$newSelectedAlbumUid"
            }

            selectedAlbumUid.value = newSelectedAlbumUid
        }
    }

    fun onReloadAlbumsClicked() {
        log.debug {
            "onReloadAlbumsClicked(): reload_albums_clicked"
        }

        update()
    }

    fun onSeeAllClicked() {
        log.debug {
            "onSeeAllClicked(): opening_overview"
        }

        eventsSubject.onNext(Event.OpenAlbumsOverview)
    }

    fun getAlbumTitle(uid: String): String? =
        albumsRepository.getLoadedAlbum(uid)?.title

    sealed interface State {
        object Loading : State
        class Ready(
            val albums: List<AlbumListItem>,
        ) : State

        object LoadingFailed : State
    }

    sealed interface Event {
        object OpenAlbumsOverview: Event
    }
}
