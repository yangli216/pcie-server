import Vue from 'vue'
import Alert from 'element-ui/lib/alert'
import Aside from 'element-ui/lib/aside'
import Button from 'element-ui/lib/button'
import Container from 'element-ui/lib/container'
import DatePicker from 'element-ui/lib/date-picker'
import Dialog from 'element-ui/lib/dialog'
import Drawer from 'element-ui/lib/drawer'
import Form from 'element-ui/lib/form'
import FormItem from 'element-ui/lib/form-item'
import Header from 'element-ui/lib/header'
import Input from 'element-ui/lib/input'
import InputNumber from 'element-ui/lib/input-number'
import Loading from 'element-ui/lib/loading'
import Main from 'element-ui/lib/main'
import Menu from 'element-ui/lib/menu'
import MenuItem from 'element-ui/lib/menu-item'
import Message from 'element-ui/lib/message'
import MessageBox from 'element-ui/lib/message-box'
import Option from 'element-ui/lib/option'
import Pagination from 'element-ui/lib/pagination'
import RadioButton from 'element-ui/lib/radio-button'
import RadioGroup from 'element-ui/lib/radio-group'
import Select from 'element-ui/lib/select'
import Switch from 'element-ui/lib/switch'
import TabPane from 'element-ui/lib/tab-pane'
import Table from 'element-ui/lib/table'
import TableColumn from 'element-ui/lib/table-column'
import Tabs from 'element-ui/lib/tabs'
import Tag from 'element-ui/lib/tag'
import 'element-ui/lib/theme-chalk/index.css'

const components = [
  Alert,
  Aside,
  Button,
  Container,
  DatePicker,
  Dialog,
  Drawer,
  Form,
  FormItem,
  Header,
  Input,
  InputNumber,
  Main,
  Menu,
  MenuItem,
  Option,
  Pagination,
  RadioButton,
  RadioGroup,
  Select,
  Switch,
  TabPane,
  Table,
  TableColumn,
  Tabs,
  Tag
]

components.forEach(component => {
  Vue.component(component.name, component)
})

Vue.use(Loading.directive)

Vue.prototype.$loading = Loading.service
Vue.prototype.$message = Message
Vue.prototype.$msgbox = MessageBox
Vue.prototype.$alert = MessageBox.alert
Vue.prototype.$confirm = MessageBox.confirm
Vue.prototype.$prompt = MessageBox.prompt
